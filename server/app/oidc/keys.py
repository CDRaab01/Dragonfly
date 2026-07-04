"""RS256 signing key(s) + JWKS for the OIDC provider.

App servers validate suite tokens with the *public* key published at JWKS — no shared secret.
A stable private key must be provided in production (`OIDC_PRIVATE_KEY`); a missing key generates
a throwaway on boot, which is fine locally but would invalidate live tokens on every restart.

Key rotation: the server signs with exactly ONE key (`OIDC_PRIVATE_KEY` / `OIDC_KEY_ID`) but can
*publish* a second, verify-only public key (`OIDC_SECONDARY_PUBLIC_KEY` / `OIDC_SECONDARY_KEY_ID`)
in the JWKS. Publishing two keys at once is what makes a zero-downtime rotation possible: during
the overlap you either pre-publish the incoming key before cutting signing over to it, or keep the
outgoing key published until the last token it signed has expired. The secondary slot never signs.
See server/DEPLOY.md "Rotating the OIDC signing key".
"""

from authlib.jose import JsonWebKey
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from app.config import settings


def _load_or_generate_private_pem() -> str:
    if settings.oidc_private_key:
        # Accept the PEM as a single-line value with literal \n escapes — survives Docker Compose
        # env-file parsing (which doesn't handle real multi-line values) and plain .env alike.
        return settings.oidc_private_key.replace("\\n", "\n")
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


def _public_jwk(pem: str, kid: str) -> dict:
    """A public JWK (RS256, use=sig) for a kid, from either a private or a public PEM.

    `import_key` reads the public half out of a private PEM, so the active signing key and a
    published-only public key both flow through here.
    """
    jwk = JsonWebKey.import_key(pem.replace("\\n", "\n"), {"kty": "RSA"})
    public = jwk.as_dict(is_private=False)
    public.update({"use": "sig", "alg": "RS256", "kid": kid})
    return public


def _published_keys(
    active_pem: str,
    active_kid: str,
    secondary_pem: str | None,
    secondary_kid: str | None,
) -> list[dict]:
    """The JWK list served at JWKS: the active signer, plus the optional verify-only second key.

    Guards against the two footguns that would silently break rotation: a half-set secondary
    (only one of the pem/kid pair), and a secondary sharing the active kid (a JWKS with duplicate
    kids resolves ambiguously — verifiers pick the first match).
    """
    keys = [_public_jwk(active_pem, active_kid)]
    if not secondary_pem and not secondary_kid:
        return keys
    if not secondary_pem or not secondary_kid:
        raise ValueError(
            "OIDC secondary key is half-configured: set BOTH OIDC_SECONDARY_PUBLIC_KEY and "
            "OIDC_SECONDARY_KEY_ID, or neither."
        )
    if secondary_kid == active_kid:
        raise ValueError(
            "OIDC_SECONDARY_KEY_ID must differ from OIDC_KEY_ID — two JWKS keys need distinct kids."
        )
    keys.append(_public_jwk(secondary_pem, secondary_kid))
    return keys


_PUBLISHED_KEYS: list[dict] = _published_keys(
    PRIVATE_KEY_PEM,
    KEY_ID,
    settings.oidc_secondary_public_key,
    settings.oidc_secondary_key_id,
)

# One KeySet for verification: it validates a token signed by ANY published key, matched by the
# token's `kid` header — so tokens signed by the outgoing key still verify during a rotation.
_PUBLIC_KEY_SET = JsonWebKey.import_key_set({"keys": _PUBLISHED_KEYS})


def public_jwks() -> dict:
    """The public key(s) as a JWKS document for `/.well-known/jwks.json`."""
    return {"keys": [dict(k) for k in _PUBLISHED_KEYS]}


def public_key_set():
    """All published public keys as an Authlib KeySet (verification only, never signs)."""
    return _PUBLIC_KEY_SET
