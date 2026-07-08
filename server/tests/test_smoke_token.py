"""Synthetic-smoke token endpoint (Magpie CLAUDE.md §9): a confidential smoke client trades its
credentials for a short-lived aud="suite" token carrying a caller-supplied throwaway email —
for an SSO-only app's post-deploy smoke, which has no register/login endpoint to script."""

from authlib.jose import jwt

from app.oidc import service_clients
from app.oidc.keys import public_key_set
from app.oidc.tokens import CROSS_APP_AUDIENCE, SUITE_AUDIENCE

FORM = {"client_id": "smoketestclient", "client_secret": "smoketestsecret"}


async def test_issues_suite_audience_token(client):
    r = await client.post("/smoke/token", data={**FORM, "subject_email": "Smoke@Example.com"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["token_type"] == "Bearer" and body["expires_in"] > 0

    claims = jwt.decode(body["access_token"], public_key_set())
    claims.validate()
    assert claims["aud"] == SUITE_AUDIENCE  # the whole point — a real app server's /auth/suite
    assert claims["aud"] != CROSS_APP_AUDIENCE  # never confusable with a cross-app token
    assert claims["email"] == "smoke@example.com"  # normalized
    assert claims["client_id"] == "smoketestclient"
    assert claims["sub"] == "smoke:smoketestclient"  # synthetic, never a real user id


async def test_bad_secret_rejected(client):
    r = await client.post(
        "/smoke/token",
        data={"client_id": "smoketestclient", "client_secret": "wrong", "subject_email": "u@e.com"},
    )
    assert r.status_code == 401
    assert r.json()["error"] == "invalid_client"


async def test_unknown_client_rejected(client):
    r = await client.post(
        "/smoke/token",
        data={"client_id": "ghost", "client_secret": "x", "subject_email": "u@e.com"},
    )
    assert r.status_code == 401


async def test_malformed_email_rejected(client):
    r = await client.post("/smoke/token", data={**FORM, "subject_email": "not-an-email"})
    assert r.status_code == 400


async def test_subject_not_in_allowlist_rejected(client):
    # F2: a valid smoke credential + a well-formed but NON-allowlisted email must not mint a
    # token — otherwise the smoke secret is an impersonate-anyone oracle across the whole suite.
    r = await client.post("/smoke/token", data={**FORM, "subject_email": "victim@example.com"})
    assert r.status_code == 403
    assert r.json()["error"] == "subject_not_allowed"


async def test_empty_allowlist_denies_everyone(client, monkeypatch):
    # Fail-closed: even the normally-allowed test email is rejected when the allowlist is empty.
    monkeypatch.setattr(service_clients, "_SMOKE_SUBJECTS", set())
    r = await client.post("/smoke/token", data={**FORM, "subject_email": "smoke@example.com"})
    assert r.status_code == 403


async def test_disabled_when_no_smoke_clients(client, monkeypatch):
    monkeypatch.setattr(service_clients, "_SMOKE_CLIENTS", {})
    r = await client.post("/smoke/token", data={**FORM, "subject_email": "u@e.com"})
    assert r.status_code == 404


async def test_smoke_credential_cannot_mint_a_cross_app_token(client):
    # A smoke client is registered only in _SMOKE_CLIENTS — it must not also satisfy
    # verify_service_client (the cross-app list), proving the two lists are truly separate.
    r = await client.post("/cross-app/token", data={**FORM, "subject_email": "u@e.com"})
    assert r.status_code == 401


def test_verify_smoke_client_constant_time():
    import app.oidc.service_clients as sc

    orig = sc._SMOKE_CLIENTS
    sc._SMOKE_CLIENTS = {"magpie-smoke": "sekret"}
    try:
        assert sc.verify_smoke_client("magpie-smoke", "sekret") is True
        assert sc.verify_smoke_client("magpie-smoke", "nope") is False
        assert sc.verify_smoke_client("ghost", "sekret") is False
    finally:
        sc._SMOKE_CLIENTS = orig
