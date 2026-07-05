import secrets
from urllib.parse import parse_qs, urlparse

from authlib.oauth2.rfc7636 import create_s256_code_challenge

from app.oidc.tokens import validate_access_token

REDIRECT = "http://127.0.0.1/callback"


def _pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    return verifier, create_s256_code_challenge(verifier)


def _authorize_params(challenge: str, **over) -> dict:
    params = {
        "client_id": "localdev",
        "redirect_uri": REDIRECT,
        "response_type": "code",
        "scope": "openid email",
        "state": "xyz",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "nonce": "n-123",
    }
    params.update(over)
    return params


async def _register_and_login(client, email: str, password: str = "Testpass123!"):
    r = await client.post("/register", json={"name": "Me", "email": email, "password": password})
    assert r.status_code in (201, 409), r.text
    r = await client.post("/login", data={"email": email, "password": password, "next": "/"})
    assert r.status_code == 303, r.text  # session cookie now on the client


async def _code_from(client, challenge: str) -> str:
    r = await client.get("/authorize", params=_authorize_params(challenge))
    assert r.status_code == 302, r.text
    return parse_qs(urlparse(r.headers["location"]).query)["code"][0]


async def test_discovery_and_jwks(client):
    d = (await client.get("/.well-known/openid-configuration")).json()
    assert d["issuer"]
    assert d["code_challenge_methods_supported"] == ["S256"]
    assert d["id_token_signing_alg_values_supported"] == ["RS256"]
    j = (await client.get("/.well-known/jwks.json")).json()
    assert j["keys"][0]["kty"] == "RSA"
    assert j["keys"][0]["use"] == "sig"


async def test_full_auth_code_pkce_flow(client):
    await _register_and_login(client, "flow@example.com")
    verifier, challenge = _pkce()

    r = await client.get("/authorize", params=_authorize_params(challenge))
    assert r.status_code == 302
    q = parse_qs(urlparse(r.headers["location"]).query)
    assert q["state"] == ["xyz"]
    code = q["code"][0]

    r = await client.post(
        "/token",
        data={
            "grant_type": "authorization_code",
            "client_id": "localdev",
            "redirect_uri": REDIRECT,
            "code": code,
            "code_verifier": verifier,
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["token_type"] == "Bearer"
    assert "id_token" in body and "refresh_token" in body

    claims = validate_access_token(body["access_token"])
    assert claims is not None
    assert claims["aud"] == "suite"
    assert claims["email"] == "flow@example.com"

    r = await client.get("/userinfo", headers={"Authorization": f"Bearer {body['access_token']}"})
    assert r.status_code == 200
    assert r.json()["email"] == "flow@example.com"

    r = await client.post(
        "/token",
        data={
            "grant_type": "refresh_token",
            "client_id": "localdev",
            "refresh_token": body["refresh_token"],
        },
    )
    assert r.status_code == 200
    assert "access_token" in r.json()


async def test_code_is_single_use(client):
    await _register_and_login(client, "single@example.com")
    verifier, challenge = _pkce()
    code = await _code_from(client, challenge)
    data = {
        "grant_type": "authorization_code",
        "client_id": "localdev",
        "redirect_uri": REDIRECT,
        "code": code,
        "code_verifier": verifier,
    }
    assert (await client.post("/token", data=data)).status_code == 200
    # Second redemption of the same code must fail.
    assert (await client.post("/token", data=data)).status_code == 400


async def test_pkce_mismatch_rejected(client):
    await _register_and_login(client, "pkce@example.com")
    _, challenge = _pkce()
    code = await _code_from(client, challenge)
    r = await client.post(
        "/token",
        data={
            "grant_type": "authorization_code",
            "client_id": "localdev",
            "redirect_uri": REDIRECT,
            "code": code,
            "code_verifier": "the-wrong-verifier-entirely",
        },
    )
    assert r.status_code == 400
    assert r.json()["error"] == "invalid_grant"


async def test_authorize_requires_login(client):
    _, challenge = _pkce()
    r = await client.get("/authorize", params=_authorize_params(challenge))
    assert r.status_code == 302
    assert "/login" in r.headers["location"]


async def test_unregistered_redirect_uri_rejected(client):
    await _register_and_login(client, "redir@example.com")
    _, challenge = _pkce()
    r = await client.get(
        "/authorize", params=_authorize_params(challenge, redirect_uri="http://evil.example/cb")
    )
    assert r.status_code == 400


async def test_missing_pkce_rejected(client):
    await _register_and_login(client, "nopkce@example.com")
    params = _authorize_params("x")
    del params["code_challenge"]
    r = await client.get("/authorize", params=params)
    assert r.status_code == 302
    assert parse_qs(urlparse(r.headers["location"]).query)["error"] == ["invalid_request"]


async def test_magpie_client_registered_with_its_redirect_uri(client):
    """Magpie CLAUDE.md's open item, closed: the `magpie` client resolves and accepts its
    Android AppAuth redirect scheme — the same registration shape as every sibling app."""
    await _register_and_login(client, "magpie-flow@example.com")
    _, challenge = _pkce()
    r = await client.get(
        "/authorize",
        params=_authorize_params(
            challenge, client_id="magpie", redirect_uri="com.magpie:/oauth2redirect"
        ),
    )
    assert r.status_code == 302
    q = parse_qs(urlparse(r.headers["location"]).query)
    assert "code" in q
