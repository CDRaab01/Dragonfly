"""Cross-app service-token endpoint (ROADMAP T2 #5).

A confidential suite backend (plate/cookbook/spotter) authenticates with its `client_id` +
`client_secret` and requests a short-lived RS256 token to make a cross-app call on behalf of a
user (identified by email). This replaces the suite's shared symmetric `CROSS_APP_SECRET`: the
issued token is validated by providers against the JWKS they already trust for SSO, scoped to
`aud="cross-app"`. The endpoint is disabled (404) until `CROSS_APP_CLIENTS` is configured.
"""

from typing import Annotated

from fastapi import APIRouter, Form, HTTPException, Request, status
from fastapi.responses import JSONResponse

from app.config import settings
from app.limiter import limiter
from app.oidc import tokens
from app.oidc.service_clients import cross_app_enabled, verify_service_client

router = APIRouter(tags=["cross-app"])


@router.post("/cross-app/token")
@limiter.limit("60/minute")
async def cross_app_token(
    request: Request,
    client_id: Annotated[str, Form()],
    client_secret: Annotated[str, Form()],
    subject_email: Annotated[str, Form()],
):
    # Disabled until confidential clients are configured — mirrors the suite's "unset ⇒ off" rule.
    if not cross_app_enabled():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Cross-app token endpoint is not enabled")
    if not verify_service_client(client_id, client_secret):
        return JSONResponse({"error": "invalid_client"}, status_code=401)
    email = subject_email.strip().lower()
    if "@" not in email:
        return JSONResponse({"error": "invalid_request"}, status_code=400)
    token = tokens.mint_cross_app_token(email=email, azp=client_id)
    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": settings.cross_app_token_expire_seconds,
    }
