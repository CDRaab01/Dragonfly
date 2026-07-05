"""Synthetic-smoke token endpoint (Magpie CLAUDE.md §9 — SSO-only apps have no register/login
to script a post-deploy smoke test against). A confidential smoke client authenticates with its
`client_id` + `client_secret` and gets a short-lived `aud="suite"` token for a caller-supplied
throwaway email, which the app server's own `POST /auth/suite` then finds-or-creates a local
session for — exactly the same trust boundary a real user's SSO login crosses, just without a
browser in the loop. The endpoint is disabled (404) until `SMOKE_CLIENTS` is configured.
"""

from typing import Annotated

from fastapi import APIRouter, Form, HTTPException, Request, status
from fastapi.responses import JSONResponse

from app.config import settings
from app.limiter import limiter
from app.oidc import tokens
from app.oidc.service_clients import smoke_enabled, verify_smoke_client

router = APIRouter(tags=["smoke"])


@router.post("/smoke/token")
@limiter.limit("60/minute")
async def smoke_token(
    request: Request,
    client_id: Annotated[str, Form()],
    client_secret: Annotated[str, Form()],
    subject_email: Annotated[str, Form()],
):
    if not smoke_enabled():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Smoke token endpoint is not enabled")
    if not verify_smoke_client(client_id, client_secret):
        return JSONResponse({"error": "invalid_client"}, status_code=401)
    email = subject_email.strip().lower()
    if "@" not in email:
        return JSONResponse({"error": "invalid_request"}, status_code=400)
    token = tokens.mint_smoke_token(email=email, azp=client_id)
    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": settings.access_token_expire_minutes * 60,
    }
