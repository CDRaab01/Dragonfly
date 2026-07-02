"""OIDC provider endpoints: discovery, JWKS, authorization-code + PKCE, token, userinfo.

Public clients only; PKCE (S256) is mandatory. The crypto (RS256 signing, JWKS, PKCE challenge)
comes from Authlib; the flow orchestration + strict redirect-URI/code binding lives here.
"""

import secrets
from datetime import datetime, timedelta, timezone
from typing import Annotated
from urllib.parse import urlencode

from authlib.oauth2.rfc7636 import create_s256_code_challenge
from fastapi import APIRouter, Depends, Form, HTTPException, Request, status
from fastapi.responses import JSONResponse, RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.oauth import AuthorizationCode, RefreshToken
from app.models.user import User
from app.oidc import tokens
from app.oidc.clients import get_client, redirect_uri_registered
from app.oidc.keys import public_jwks

router = APIRouter(tags=["oidc"])

DbDep = Annotated[AsyncSession, Depends(get_db)]

CODE_TTL_SECONDS = 60


@router.get("/.well-known/openid-configuration")
async def discovery() -> dict:
    base = settings.issuer.rstrip("/")
    return {
        "issuer": settings.issuer,
        "authorization_endpoint": f"{base}/authorize",
        "token_endpoint": f"{base}/token",
        "userinfo_endpoint": f"{base}/userinfo",
        "jwks_uri": f"{base}/.well-known/jwks.json",
        "response_types_supported": ["code"],
        "grant_types_supported": ["authorization_code", "refresh_token"],
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
        "scopes_supported": ["openid", "email", "profile"],
        "token_endpoint_auth_methods_supported": ["none"],
        "code_challenge_methods_supported": ["S256"],
    }


@router.get("/.well-known/jwks.json")
async def jwks() -> dict:
    return public_jwks()


def _redirect_error(redirect_uri: str, error: str, state: str | None) -> RedirectResponse:
    params = {"error": error}
    if state:
        params["state"] = state
    sep = "&" if "?" in redirect_uri else "?"
    return RedirectResponse(f"{redirect_uri}{sep}{urlencode(params)}", status_code=302)


@router.get("/authorize")
async def authorize(
    request: Request,
    db: DbDep,
    client_id: str,
    redirect_uri: str,
    response_type: str = "code",
    scope: str = "openid",
    state: str | None = None,
    code_challenge: str | None = None,
    code_challenge_method: str = "S256",
    nonce: str | None = None,
):
    client = get_client(client_id)
    # Invalid client / redirect: fail loudly here — never redirect to an unregistered URI.
    if client is None or not redirect_uri_registered(client, redirect_uri):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown client_id or redirect_uri")
    if response_type != "code":
        return _redirect_error(redirect_uri, "unsupported_response_type", state)
    if not code_challenge or code_challenge_method != "S256":
        return _redirect_error(redirect_uri, "invalid_request", state)  # PKCE S256 required
    if "openid" not in scope.split():
        return _redirect_error(redirect_uri, "invalid_scope", state)

    user_id = request.session.get("user_id")
    if not user_id:
        # Not signed in → send to the login page, which returns here after auth.
        login_next = f"/authorize?{urlencode(dict(request.query_params))}"
        return RedirectResponse(f"/login?{urlencode({'next': login_next})}", status_code=302)

    code = secrets.token_urlsafe(32)
    db.add(
        AuthorizationCode(
            code=code,
            client_id=client_id,
            redirect_uri=redirect_uri,
            user_id=user_id,
            scope=scope,
            nonce=nonce,
            code_challenge=code_challenge,
            code_challenge_method=code_challenge_method,
            expires_at=datetime.now(timezone.utc) + timedelta(seconds=CODE_TTL_SECONDS),
        )
    )
    await db.commit()
    params = {"code": code}
    if state:
        params["state"] = state
    sep = "&" if "?" in redirect_uri else "?"
    return RedirectResponse(f"{redirect_uri}{sep}{urlencode(params)}", status_code=302)


def _token_error(error: str, code: int = 400) -> JSONResponse:
    return JSONResponse({"error": error}, status_code=code)


async def _issue_tokens(db: AsyncSession, user: User, client_id: str, scope: str) -> dict:
    refresh = tokens.new_refresh_token()
    db.add(
        RefreshToken(
            token=refresh,
            user_id=user.id,
            client_id=client_id,
            scope=scope,
            expires_at=datetime.now(timezone.utc)
            + timedelta(days=settings.refresh_token_expire_days),
        )
    )
    access = tokens.mint_access_token(
        sub=user.id, email=user.email, client_id=client_id, scope=scope
    )
    body = {
        "access_token": access,
        "token_type": "Bearer",
        "expires_in": settings.access_token_expire_minutes * 60,
        "refresh_token": refresh,
        "scope": scope,
    }
    if "openid" in scope.split():
        body["id_token"] = tokens.mint_id_token(
            sub=user.id, email=user.email, name=user.name, client_id=client_id, nonce=None
        )
    return body


@router.post("/token")
async def token(
    request: Request,
    db: DbDep,
    grant_type: Annotated[str, Form()],
    client_id: Annotated[str, Form()],
    code: Annotated[str | None, Form()] = None,
    redirect_uri: Annotated[str | None, Form()] = None,
    code_verifier: Annotated[str | None, Form()] = None,
    refresh_token: Annotated[str | None, Form()] = None,
):
    if get_client(client_id) is None:
        return _token_error("invalid_client", 401)

    if grant_type == "authorization_code":
        if not code or not redirect_uri or not code_verifier:
            return _token_error("invalid_request")
        auth_code = await db.get(AuthorizationCode, code)
        if auth_code is None:
            return _token_error("invalid_grant")
        # Single-use: consume immediately, whatever happens next.
        await db.delete(auth_code)
        await db.commit()
        if (
            auth_code.client_id != client_id
            or auth_code.redirect_uri != redirect_uri
            or auth_code.expires_at < datetime.now(timezone.utc)
        ):
            return _token_error("invalid_grant")
        if create_s256_code_challenge(code_verifier) != auth_code.code_challenge:
            return _token_error("invalid_grant")  # PKCE mismatch
        user = await db.get(User, auth_code.user_id)
        if user is None:
            return _token_error("invalid_grant")
        body = await _issue_tokens(db, user, client_id, auth_code.scope)
        # id_token nonce must echo the authorize request's nonce.
        if "openid" in auth_code.scope.split():
            body["id_token"] = tokens.mint_id_token(
                sub=user.id,
                email=user.email,
                name=user.name,
                client_id=client_id,
                nonce=auth_code.nonce,
            )
        await db.commit()
        return JSONResponse(body)

    if grant_type == "refresh_token":
        if not refresh_token:
            return _token_error("invalid_request")
        stored = await db.get(RefreshToken, refresh_token)
        if (
            stored is None
            or stored.revoked
            or stored.client_id != client_id
            or stored.expires_at < datetime.now(timezone.utc)
        ):
            return _token_error("invalid_grant")
        user = await db.get(User, stored.user_id)
        if user is None:
            return _token_error("invalid_grant")
        stored.revoked = True  # rotate
        body = await _issue_tokens(db, user, client_id, stored.scope)
        await db.commit()
        return JSONResponse(body)

    return _token_error("unsupported_grant_type")


@router.get("/userinfo")
async def userinfo(request: Request, db: DbDep) -> dict:
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
    return {"sub": user.id, "email": user.email, "name": user.name}
