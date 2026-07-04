"""GET /export — identity-account export, authenticated by a suite access token (ROADMAP T3 #6)."""

from app.database import AsyncSessionLocal
from app.models.user import User
from app.oidc import tokens


async def _make_user(email: str = "export@example.com") -> User:
    async with AsyncSessionLocal() as s:
        user = User(email=email, name="Export User", password_hash="x")
        s.add(user)
        await s.commit()
        await s.refresh(user)
        return user


def _token(user: User) -> str:
    return tokens.mint_access_token(
        sub=user.id, email=user.email, client_id="localdev", scope="openid"
    )


async def test_export_returns_account(client):
    user = await _make_user("exportme@example.com")
    r = await client.get("/export", headers={"Authorization": f"Bearer {_token(user)}"})
    assert r.status_code == 200, r.text
    assert "attachment" in r.headers.get("content-disposition", "")

    data = r.json()
    assert data["app"] == "dragonfly-id"
    assert data["schema_version"] >= 1
    assert data["user"]["email"] == "exportme@example.com"
    # Secrets never leave the server.
    assert "password_hash" not in data["user"]
    assert "reset_token_hash" not in data["user"]
    assert isinstance(data["user"]["created_at"], str)  # datetime → ISO string


async def test_export_requires_bearer(client):
    assert (await client.get("/export")).status_code == 401


async def test_export_rejects_bad_token(client):
    r = await client.get("/export", headers={"Authorization": "Bearer not-a-jwt"})
    assert r.status_code == 401
