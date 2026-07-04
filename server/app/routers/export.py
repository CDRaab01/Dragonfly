"""GET /export — download the signed-in user's identity-account data (ROADMAP T3 #6).

dragonfly-id's only user content is the account profile; OAuth session state (refresh tokens,
authorization codes) is deliberately excluded. Authenticated by a suite access token, exactly like
/userinfo. Secret columns (password + reset-token hashes) are redacted.
"""

import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.user import User
from app.oidc import tokens

router = APIRouter(tags=["export"])

DbDep = Annotated[AsyncSession, Depends(get_db)]

EXPORT_SCHEMA_VERSION = 1
_REDACT = {"password_hash", "reset_token_hash", "reset_token_expires_at"}


def _serialize_user(user: User) -> dict:
    out = {}
    for c in user.__table__.columns:
        if c.name in _REDACT:
            continue
        value = getattr(user, c.name)
        out[c.name] = value.isoformat() if isinstance(value, datetime.datetime) else value
    return out


@router.get("/export")
@limiter.limit("5/minute")
async def export(request: Request, db: DbDep) -> JSONResponse:
    """The user's identity-account export, authenticated by a suite access token (like /userinfo)."""
    auth = request.headers.get("authorization", "")
    if not auth.startswith("Bearer "):
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED, "Missing bearer token", {"WWW-Authenticate": "Bearer"}
        )
    claims = tokens.validate_access_token(auth[7:])
    if claims is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED, "Invalid token", {"WWW-Authenticate": "Bearer"}
        )
    user = await db.get(User, claims["sub"])
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Unknown subject")

    data = {
        "app": "dragonfly-id",
        "schema_version": EXPORT_SCHEMA_VERSION,
        "exported_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "user": _serialize_user(user),
    }
    filename = f"dragonfly-id-export-{datetime.date.today().isoformat()}.json"
    return JSONResponse(
        data, headers={"Content-Disposition": f'attachment; filename="{filename}"'}
    )
