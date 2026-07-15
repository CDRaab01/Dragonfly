"""Account + interactive login for the OIDC flow.

Registration seeds the suite identity (one person, for now). Login is the browser/Custom-Tab step
of the authorization-code flow: it authenticates and sets a session cookie, then bounces back to
the `next` (the /authorize URL), which can now issue a code.
"""

from html import escape
from typing import Annotated

from fastapi import APIRouter, Depends, Form, HTTPException, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.limiter import limiter
from app.models.user import User
from app.security import hash_password, verify_password

router = APIRouter(tags=["accounts"])

DbDep = Annotated[AsyncSession, Depends(get_db)]


class RegisterIn(BaseModel):
    name: str = Field(default="", max_length=200)
    email: EmailStr
    password: str = Field(min_length=8, max_length=200)
    invite_code: str | None = None


async def _create_user(
    db: AsyncSession, *, name: str, email: str, password: str, invite_code: str | None
) -> User:
    """Shared account creation for the JSON API and the browser registration form."""
    if settings.registration_invite_code and invite_code != settings.registration_invite_code:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Invalid or missing invite code")
    if len(password) < 8:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, "Password must be at least 8 characters"
        )
    normalized = email.strip().lower()
    existing = await db.execute(select(User).where(User.email == normalized))
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "Email already registered")
    user = User(email=normalized, name=name.strip(), password_hash=hash_password(password))
    db.add(user)
    await db.commit()
    return user


@router.post("/register", status_code=status.HTTP_201_CREATED)
@limiter.limit("5/minute")
async def register(request: Request, body: RegisterIn, db: DbDep) -> dict:
    user = await _create_user(
        db, name=body.name, email=body.email, password=body.password, invite_code=body.invite_code
    )
    return {"id": user.id, "email": user.email}


def _login_page(next_url: str, error: str | None = None) -> str:
    err = f'<p style="color:#c00">{escape(error)}</p>' if error else ""
    return f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sign in — Dragonfly</title></head>
<body style="font-family:system-ui;max-width:360px;margin:8vh auto;padding:0 1rem">
<h1 style="font-size:1.3rem">Dragonfly sign-in</h1>{err}
<form method="post" action="/login">
<input type="hidden" name="next" value="{escape(next_url)}">
<label>Email<br><input name="email" type="email" required style="width:100%;padding:.5rem"></label><br><br>
<label>Password<br><input name="password" type="password" required style="width:100%;padding:.5rem"></label><br><br>
<button type="submit" style="padding:.6rem 1rem">Sign in</button>
</form>
<p style="margin-top:1rem"><a href="/register?next={escape(next_url)}">Create an account</a></p>
<p style="margin-top:.25rem"><a href="/account">Manage your account</a></p>
</body></html>"""


@router.get("/login", response_class=HTMLResponse)
async def login_form(next: str = "/") -> HTMLResponse:
    return HTMLResponse(_login_page(next))


@router.post("/login")
@limiter.limit("10/minute")
async def login_submit(
    request: Request,
    db: DbDep,
    email: Annotated[str, Form()],
    password: Annotated[str, Form()],
    next: Annotated[str, Form()] = "/",
):
    result = await db.execute(select(User).where(User.email == email.lower()))
    user = result.scalar_one_or_none()
    if user is None or not verify_password(password, user.password_hash):
        return HTMLResponse(_login_page(next, "Incorrect email or password"), status_code=401)
    request.session["user_id"] = user.id
    # 303 so the browser re-issues the next (the /authorize URL) as a GET.
    return RedirectResponse(next, status_code=status.HTTP_303_SEE_OTHER)


def _register_page(next_url: str, error: str | None = None, invite_required: bool = False) -> str:
    err = f'<p style="color:#c00">{escape(error)}</p>' if error else ""
    invite_field = (
        '<label>Invite code<br><input name="invite_code" required '
        'style="width:100%;padding:.5rem"></label><br><br>'
        if invite_required
        else ""
    )
    return f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Create account — Dragonfly</title></head>
<body style="font-family:system-ui;max-width:360px;margin:8vh auto;padding:0 1rem">
<h1 style="font-size:1.3rem">Create your Dragonfly account</h1>{err}
<form method="post" action="/register/submit">
<input type="hidden" name="next" value="{escape(next_url)}">
<label>Name<br><input name="name" style="width:100%;padding:.5rem"></label><br><br>
<label>Email<br><input name="email" type="email" required style="width:100%;padding:.5rem"></label><br><br>
<label>Password<br><input name="password" type="password" required minlength="8" style="width:100%;padding:.5rem"></label><br><br>
{invite_field}<button type="submit" style="padding:.6rem 1rem">Create account</button>
</form>
<p style="margin-top:1rem"><a href="/login?next={escape(next_url)}">Already have an account? Sign in</a></p>
</body></html>"""


@router.get("/register", response_class=HTMLResponse)
async def register_form(next: str = "/") -> HTMLResponse:
    invite_required = bool(settings.registration_invite_code)
    return HTMLResponse(_register_page(next, invite_required=invite_required))


@router.post("/register/submit")
@limiter.limit("5/minute")
async def register_submit(
    request: Request,
    db: DbDep,
    email: Annotated[str, Form()],
    password: Annotated[str, Form()],
    name: Annotated[str, Form()] = "",
    invite_code: Annotated[str, Form()] = "",
    next: Annotated[str, Form()] = "/",
):
    # Only ever bounce back to a same-origin relative path (guards against open redirect).
    safe_next = next if next.startswith("/") else "/"
    try:
        user = await _create_user(
            db, name=name, email=email, password=password, invite_code=invite_code or None
        )
    except HTTPException as exc:
        return HTMLResponse(
            _register_page(
                safe_next, str(exc.detail), invite_required=bool(settings.registration_invite_code)
            ),
            status_code=exc.status_code,
        )
    # New account → sign them in immediately and continue the auth-code flow (the `next`).
    request.session["user_id"] = user.id
    return RedirectResponse(safe_next, status_code=status.HTTP_303_SEE_OTHER)


@router.post("/logout")
async def logout(request: Request) -> dict:
    request.session.clear()
    return {"status": "signed out"}
