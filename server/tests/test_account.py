"""Self-service account surface: password change + active-session list/revoke."""

import re
import secrets
from urllib.parse import parse_qs, urlparse

from authlib.oauth2.rfc7636 import create_s256_code_challenge

REDIRECT = "http://127.0.0.1/callback"


def _pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    return verifier, create_s256_code_challenge(verifier)


async def _register_and_login(client, email: str, password: str = "Testpass123!"):
    r = await client.post("/register", json={"name": "Me", "email": email, "password": password})
    assert r.status_code in (201, 409), r.text
    r = await client.post("/login", data={"email": email, "password": password, "next": "/"})
    assert r.status_code == 303, r.text  # session cookie now on the client


async def _mint_refresh_token(client) -> str:
    """Run the real PKCE auth-code flow to get a genuine, revocable refresh token."""
    verifier, challenge = _pkce()
    params = {
        "client_id": "localdev",
        "redirect_uri": REDIRECT,
        "response_type": "code",
        "scope": "openid email",
        "state": "s",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "nonce": "n",
    }
    r = await client.get("/authorize", params=params)
    assert r.status_code == 302, r.text
    code = parse_qs(urlparse(r.headers["location"]).query)["code"][0]
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
    return r.json()["refresh_token"]


def _session_ids(html: str) -> list[str]:
    return re.findall(r'name="session_id" value="([^"]+)"', html)


async def _refresh(client, refresh_token: str):
    return await client.post(
        "/token",
        data={
            "grant_type": "refresh_token",
            "client_id": "localdev",
            "refresh_token": refresh_token,
        },
    )


async def test_account_requires_login(client):
    r = await client.get("/account", follow_redirects=False)
    assert r.status_code == 303
    assert "/login?next=/account" in r.headers["location"]


async def test_active_session_listed_and_revoke_blocks_refresh(client):
    await _register_and_login(client, "revoke@example.com")
    refresh = await _mint_refresh_token(client)

    page = await client.get("/account")
    assert page.status_code == 200
    ids = _session_ids(page.text)
    assert len(ids) == 1, "the freshly minted session should be listed once"

    revoked = await client.post("/account/sessions/revoke", data={"session_id": ids[0]})
    assert revoked.status_code == 200
    assert "Session revoked" in revoked.text
    assert _session_ids(revoked.text) == []

    # The revoked token can no longer be exchanged — the app is signed out at its next refresh.
    r = await _refresh(client, refresh)
    assert r.status_code == 400
    assert r.json()["error"] == "invalid_grant"


async def test_revoke_all_signs_out_every_app(client):
    await _register_and_login(client, "revokeall@example.com")
    r1 = await _mint_refresh_token(client)
    r2 = await _mint_refresh_token(client)

    page = await client.get("/account")
    assert len(_session_ids(page.text)) == 2

    done = await client.post("/account/sessions/revoke-all")
    assert done.status_code == 200
    assert "Signed out of 2 app(s)" in done.text
    assert _session_ids(done.text) == []

    assert (await _refresh(client, r1)).status_code == 400
    assert (await _refresh(client, r2)).status_code == 400


async def test_change_password(client):
    await _register_and_login(client, "pw@example.com", password="Oldpass123!")

    wrong = await client.post(
        "/account/password",
        data={
            "current_password": "nope",
            "new_password": "Newpass123!",
            "confirm_password": "Newpass123!",
        },
    )
    assert wrong.status_code == 400
    assert "incorrect" in wrong.text.lower()

    mismatch = await client.post(
        "/account/password",
        data={
            "current_password": "Oldpass123!",
            "new_password": "Newpass123!",
            "confirm_password": "Different123!",
        },
    )
    assert mismatch.status_code == 400
    assert "do not match" in mismatch.text.lower()

    ok = await client.post(
        "/account/password",
        data={
            "current_password": "Oldpass123!",
            "new_password": "Newpass123!",
            "confirm_password": "Newpass123!",
        },
    )
    assert ok.status_code == 200
    assert "Password updated" in ok.text

    # The new password works; the old one no longer does.
    good = await client.post(
        "/login", data={"email": "pw@example.com", "password": "Newpass123!", "next": "/"}
    )
    assert good.status_code == 303
    bad = await client.post(
        "/login", data={"email": "pw@example.com", "password": "Oldpass123!", "next": "/"}
    )
    assert bad.status_code == 401
