"""Weekly digest read + manual generate (ROADMAP3 Tier W1).

Owner-scoped and single-key: the hub presents ``X-Digest-Key`` (stored in its settings) to read the
latest digest. Not tied to a user session — the hub has no account. Disabled (404) until a read key
is configured, mirroring the suite's "unset ⇒ off" rule.
"""

import json
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.digest.service import generate_and_store, latest

router = APIRouter(prefix="/digest", tags=["digest"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


def _require_key(x_digest_key: str | None) -> None:
    if not settings.digest_read_key:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Digest is not enabled")
    if x_digest_key != settings.digest_read_key:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid digest key")


def _serialize(d) -> dict:
    return {
        "week_start": d.week_start,
        "week_end": d.week_end,
        "narrative": d.narrative,
        "domains": json.loads(d.data_json),
        "generated_at": d.generated_at.isoformat() if d.generated_at else None,
    }


@router.get("/weekly")
async def get_weekly(db: DbSession, x_digest_key: Annotated[str | None, Header()] = None):
    _require_key(x_digest_key)
    d = await latest(db)
    if d is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No digest generated yet")
    return _serialize(d)


@router.post("/generate")
async def generate_now(db: DbSession, x_digest_key: Annotated[str | None, Header()] = None):
    """Generate this week's digest now (owner tool / on-demand). The scheduler calls the same path."""
    _require_key(x_digest_key)
    if not settings.digest_owner_email:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "digest_owner_email is not configured")
    d = await generate_and_store(db)
    return _serialize(d)
