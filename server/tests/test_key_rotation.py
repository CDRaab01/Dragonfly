"""Signing-key rotation: the JWKS may publish a second, verify-only key so tokens signed by the
outgoing key keep validating during the overlap. See server/DEPLOY.md "Rotating the OIDC signing
key"."""

import time

import pytest
from authlib.jose import JsonWebKey, jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from app.oidc import keys as keymod
from app.oidc.keys import _published_keys, public_jwks
from app.oidc.tokens import mint_access_token, validate_access_token


def _rsa_pem() -> str:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode()


def test_default_jwks_publishes_single_key():
    jwks = public_jwks()
    assert len(jwks["keys"]) == 1
    assert jwks["keys"][0]["kid"] == keymod.KEY_ID
    assert jwks["keys"][0]["alg"] == "RS256"
    # No private material ever leaks into the published document.
    assert "d" not in jwks["keys"][0]


def test_minted_token_validates_against_active_key():
    token = mint_access_token(
        sub="u1", email="u1@example.com", client_id="localdev", scope="openid"
    )
    claims = validate_access_token(token)
    assert claims is not None and claims["email"] == "u1@example.com"


def test_secondary_key_published_and_validates():
    """With a second key published, the JWKS carries both kids and a token signed by EITHER key
    verifies against the set — the property a rotation overlap depends on."""
    active_pem = _rsa_pem()
    secondary_pem = _rsa_pem()
    published = _published_keys(active_pem, "id-active", secondary_pem, "id-secondary")
    assert [k["kid"] for k in published] == ["id-active", "id-secondary"]

    keyset = JsonWebKey.import_key_set({"keys": published})
    now = int(time.time())
    for signing_pem, kid in ((active_pem, "id-active"), (secondary_pem, "id-secondary")):
        token = jwt.encode(
            {"alg": "RS256", "kid": kid, "typ": "JWT"},
            {"sub": "x", "iat": now, "exp": now + 300},
            signing_pem,
        ).decode()
        claims = jwt.decode(token, keyset)
        claims.validate()
        assert claims["sub"] == "x"


def test_no_secondary_yields_single_key():
    assert len(_published_keys(_rsa_pem(), "id-active", None, None)) == 1


def test_half_configured_secondary_rejected():
    active = _rsa_pem()
    with pytest.raises(ValueError, match="half-configured"):
        _published_keys(active, "id-active", _rsa_pem(), None)
    with pytest.raises(ValueError, match="half-configured"):
        _published_keys(active, "id-active", None, "id-secondary")


def test_colliding_kid_rejected():
    with pytest.raises(ValueError, match="differ from OIDC_KEY_ID"):
        _published_keys(_rsa_pem(), "same-kid", _rsa_pem(), "same-kid")
