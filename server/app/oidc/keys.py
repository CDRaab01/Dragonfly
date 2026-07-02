"""RS256 signing key + JWKS for the OIDC provider.

App servers validate suite tokens with the *public* key published at JWKS — no shared secret.
A stable private key must be provided in production (`OIDC_PRIVATE_KEY`); a missing key generates
a throwaway on boot, which is fine locally but would invalidate live tokens on every restart.
"""

from authlib.jose import JsonWebKey
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from app.config import settings


def _load_or_generate_private_pem() -> str:
    if settings.oidc_private_key:
        return settings.oidc_private_key
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode()


PRIVATE_KEY_PEM: str = _load_or_generate_private_pem()
KEY_ID: str = settings.oidc_key_id

# Public half, derived once — used to verify our own tokens (e.g. at /userinfo).
_private = serialization.load_pem_private_key(PRIVATE_KEY_PEM.encode(), password=None)
PUBLIC_KEY_PEM: str = (
    _private.public_key()
    .public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    .decode()
)


def public_jwks() -> dict:
    """The public half as a JWKS document for `/.well-known/jwks.json`."""
    jwk = JsonWebKey.import_key(PRIVATE_KEY_PEM, {"kty": "RSA"})
    public = jwk.as_dict(is_private=False)
    public.update({"use": "sig", "alg": "RS256", "kid": KEY_ID})
    return {"keys": [public]}
