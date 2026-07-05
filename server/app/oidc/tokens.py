"""OIDC token minting + validation (RS256 via Authlib JOSE).

- access token: `aud="suite"` — what each app server validates before trading it for a local
  session at `POST /auth/suite`.
- id token: `aud=<client_id>` per OIDC; carries identity claims for the client.
- refresh tokens are opaque random strings (stored server-side so they can be revoked), not JWTs.
"""

import secrets
import time

from authlib.jose import jwt
from authlib.jose.errors import JoseError

from app.config import settings
from app.oidc.keys import KEY_ID, PRIVATE_KEY_PEM, public_key_set

SUITE_AUDIENCE = "suite"
# Cross-app service tokens (ROADMAP T2 #5) carry their own audience so a user's SSO access token
# (aud="suite") can never be replayed on a cross-app provider surface, and vice versa.
CROSS_APP_AUDIENCE = "cross-app"


def _sign(payload: dict) -> str:
    header = {"alg": "RS256", "kid": KEY_ID, "typ": "JWT"}
    return jwt.encode(header, payload, PRIVATE_KEY_PEM).decode()


def mint_access_token(*, sub: str, email: str, client_id: str, scope: str) -> str:
    now = int(time.time())
    return _sign(
        {
            "iss": settings.issuer,
            "sub": sub,
            "aud": SUITE_AUDIENCE,
            "email": email,
            "client_id": client_id,
            "scope": scope,
            "iat": now,
            "exp": now + settings.access_token_expire_minutes * 60,
        }
    )


def mint_id_token(*, sub: str, email: str, name: str, client_id: str, nonce: str | None) -> str:
    now = int(time.time())
    payload = {
        "iss": settings.issuer,
        "sub": sub,
        "aud": client_id,
        "email": email,
        "name": name,
        "iat": now,
        "exp": now + settings.access_token_expire_minutes * 60,
    }
    if nonce:
        payload["nonce"] = nonce
    return _sign(payload)


def mint_cross_app_token(*, email: str, azp: str) -> str:
    """A short-lived RS256 service token authorizing a cross-app call on behalf of `email`.

    `azp` is the confidential client (calling server) that requested it. Providers validate this
    against the same JWKS they already trust for SSO, but require `aud="cross-app"`, then resolve
    the local user by `email` — the only identity stable across the apps' independent user tables.
    """
    now = int(time.time())
    return _sign(
        {
            "iss": settings.issuer,
            "aud": CROSS_APP_AUDIENCE,
            "azp": azp,
            "email": email,
            "type": "cross_app",
            "iat": now,
            "exp": now + settings.cross_app_token_expire_seconds,
        }
    )


def mint_smoke_token(*, email: str, azp: str) -> str:
    """A short-lived aud="suite" token for a caller-supplied throwaway email — used by an
    SSO-only app's post-deploy synthetic smoke (Magpie CLAUDE.md §9), which has no
    register/login endpoint of its own to script against. `sub` is synthetic (there is no real
    dragonfly-id user backing this call): app servers' suite-login only reads `email`, never
    `sub`, to find-or-create the local account, so this is safe.
    """
    now = int(time.time())
    return _sign(
        {
            "iss": settings.issuer,
            "sub": f"smoke:{azp}",
            "aud": SUITE_AUDIENCE,
            "email": email,
            "client_id": azp,
            "scope": "openid",
            "iat": now,
            "exp": now + settings.access_token_expire_minutes * 60,
        }
    )


def new_refresh_token() -> str:
    return secrets.token_urlsafe(48)


def validate_access_token(token: str) -> dict | None:
    """Verify signature (via our own public JWKS) + issuer/audience/expiry. Returns claims or None.
    App servers do the equivalent using the published JWKS; this is for our own `/userinfo`.
    """
    try:
        # Verify against every published key (matched by the token's `kid`), so a token signed by
        # the outgoing key still validates during a rotation overlap. An unknown/missing kid makes
        # Authlib's KeySet raise ValueError — treat it, like a bad signature, as an invalid token.
        claims = jwt.decode(token, public_key_set())
        claims.validate()  # exp/nbf
    except (JoseError, ValueError, KeyError):
        return None
    if claims.get("iss") != settings.issuer or claims.get("aud") != SUITE_AUDIENCE:
        return None
    return dict(claims)
