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
