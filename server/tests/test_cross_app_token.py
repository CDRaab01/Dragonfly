"""Cross-app service-token endpoint (ROADMAP T2 #5): a confidential client trades its credentials
for a short-lived RS256 token scoped to aud="cross-app", carrying a subject email."""

import pytest
from authlib.jose import jwt

from app.oidc import service_clients
from app.oidc.keys import public_key_set
from app.oidc.service_clients import _parse
from app.oidc.tokens import CROSS_APP_AUDIENCE, SUITE_AUDIENCE

FORM = {"client_id": "testclient", "client_secret": "testsecret"}


async def test_issues_cross_app_token(client):
    r = await client.post("/cross-app/token", data={**FORM, "subject_email": "User@Example.com"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["token_type"] == "Bearer" and body["expires_in"] > 0

    claims = jwt.decode(body["access_token"], public_key_set())
    claims.validate()  # exp/iat
    assert claims["aud"] == CROSS_APP_AUDIENCE
    assert claims["aud"] != SUITE_AUDIENCE  # scoped away from SSO user tokens
    assert claims["email"] == "user@example.com"  # normalized
    assert claims["azp"] == "testclient"
    assert claims["type"] == "cross_app"


async def test_bad_secret_rejected(client):
    r = await client.post(
        "/cross-app/token",
        data={"client_id": "testclient", "client_secret": "wrong", "subject_email": "u@e.com"},
    )
    assert r.status_code == 401
    assert r.json()["error"] == "invalid_client"


async def test_unknown_client_rejected(client):
    r = await client.post(
        "/cross-app/token",
        data={"client_id": "ghost", "client_secret": "x", "subject_email": "u@e.com"},
    )
    assert r.status_code == 401


async def test_malformed_email_rejected(client):
    r = await client.post("/cross-app/token", data={**FORM, "subject_email": "not-an-email"})
    assert r.status_code == 400


async def test_disabled_when_no_clients(client, monkeypatch):
    # Simulate an unconfigured deploy: no confidential clients ⇒ endpoint 404s.
    monkeypatch.setattr(service_clients, "_CLIENTS", {})
    r = await client.post("/cross-app/token", data={**FORM, "subject_email": "u@e.com"})
    assert r.status_code == 404


def test_parse_and_verify():
    assert _parse("") == {}
    assert _parse("plate:s1, cookbook:s2") == {"plate": "s1", "cookbook": "s2"}
    with pytest.raises(ValueError, match="malformed"):
        _parse("plate")  # no secret


def test_verify_service_client_constant_time():
    monkey = {"plate": "sekret"}
    # Directly exercise the compare against a known table.
    import app.oidc.service_clients as sc

    orig = sc._CLIENTS
    sc._CLIENTS = monkey
    try:
        assert sc.verify_service_client("plate", "sekret") is True
        assert sc.verify_service_client("plate", "nope") is False
        assert sc.verify_service_client("ghost", "sekret") is False
    finally:
        sc._CLIENTS = orig
