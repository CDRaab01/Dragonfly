"""Browser registration flow (GET /register + POST /register/submit)."""


async def test_register_form_renders(client):
    r = await client.get("/register", params={"next": "/authorize?x=1"})
    assert r.status_code == 200
    assert "Create your Dragonfly account" in r.text
    # The hidden `next` is carried through so registration continues the auth-code flow.
    assert "/authorize?x=1" in r.text


async def test_register_submit_creates_and_signs_in(client):
    r = await client.post(
        "/register/submit",
        data={
            "name": "New Person",
            "email": "formreg@example.com",
            "password": "password123",
            "next": "/authorize?x=1",
        },
        follow_redirects=False,
    )
    assert r.status_code == 303
    assert r.headers["location"] == "/authorize?x=1"
    # A session cookie is set → the new account is signed in immediately.
    assert "session" in r.headers.get("set-cookie", "")


async def test_register_duplicate_email_rejected(client):
    data = {"email": "dupform@example.com", "password": "password123", "next": "/"}
    assert (await client.post("/register/submit", data=data, follow_redirects=False)).status_code == 303
    dup = await client.post("/register/submit", data=data, follow_redirects=False)
    assert dup.status_code == 409
    assert "already registered" in dup.text.lower()


async def test_register_short_password_rejected(client):
    r = await client.post(
        "/register/submit",
        data={"email": "shortpw@example.com", "password": "short", "next": "/"},
        follow_redirects=False,
    )
    assert r.status_code == 422
    assert "at least 8" in r.text.lower()


async def test_register_open_redirect_guarded(client):
    r = await client.post(
        "/register/submit",
        data={"email": "evilform@example.com", "password": "password123", "next": "https://evil.example"},
        follow_redirects=False,
    )
    assert r.status_code == 303
    assert r.headers["location"] == "/"


async def test_login_page_links_to_register(client):
    r = await client.get("/login", params={"next": "/authorize?x=1"})
    assert "/register?next=" in r.text
