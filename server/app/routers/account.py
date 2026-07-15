"""Self-service account surface for dragonfly-id (Road to 1.0 #4 / hardening #2).

Session-cookie-gated HTML pages (same auth as /login) that let the signed-in person:
- change their password, and
- see their active sessions (one live refresh token per app sign-in) and revoke them —
  individually or all at once ("the one that matters the day a phone is lost").

Revoking a refresh token relies on the /token refresh path already rejecting `revoked` tokens,
so a revoked session can mint no further access tokens (the app is signed out at its next refresh).
Sessions are referenced by the refresh token's surrogate `id`, never its secret value.
"""

from datetime import datetime, timezone
from html import escape
from typing import Annotated

from fastapi import APIRouter, Depends, Form, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.oauth import RefreshToken
from app.models.user import User
from app.security import hash_password, verify_password

router = APIRouter(tags=["account"])

DbDep = Annotated[AsyncSession, Depends(get_db)]

# Friendly display names for the static OIDC clients (fallback: the id, title-cased).
_CLIENT_LABELS = {
    "spotter": "Spotter",
    "plate": "Plate",
    "cookbook": "Cookbook",
    "magpie": "Magpie",
    "dragonfly": "Dragonfly",
}


async def _session_user(request: Request, db: AsyncSession) -> User | None:
    user_id = request.session.get("user_id")
    if not user_id:
        return None
    return await db.get(User, user_id)


def _client_label(client_id: str) -> str:
    return _CLIENT_LABELS.get(client_id, client_id.replace("_", " ").title())


async def _active_sessions(db: AsyncSession, user_id: str) -> list[RefreshToken]:
    """Live sessions = a user's non-revoked, unexpired refresh tokens, newest first.

    Refresh rotation revokes the old token on every use, so at most one row per app sign-in chain
    survives here — i.e. one row per currently-signed-in app.
    """
    now = datetime.now(timezone.utc)
    result = await db.execute(
        select(RefreshToken)
        .where(
            RefreshToken.user_id == user_id,
            RefreshToken.revoked == False,  # noqa: E712
            RefreshToken.expires_at > now,
        )
        .order_by(RefreshToken.created_at.desc())
    )
    return list(result.scalars().all())


def _page(user: User, sessions: list[RefreshToken], notice: str = "", error: str = "") -> str:
    notice_html = f'<p style="color:#080">{escape(notice)}</p>' if notice else ""
    error_html = f'<p style="color:#c00">{escape(error)}</p>' if error else ""
    if sessions:
        rows = "".join(
            f"""<li style="margin:.5rem 0;display:flex;justify-content:space-between;align-items:center;gap:1rem">
<span><strong>{escape(_client_label(s.client_id))}</strong><br>
<small style="color:#666">signed in {s.created_at:%Y-%m-%d %H:%M} UTC</small></span>
<form method="post" action="/account/sessions/revoke" style="margin:0">
<input type="hidden" name="session_id" value="{escape(s.id)}">
<button type="submit" style="padding:.35rem .7rem">Revoke</button></form></li>"""
            for s in sessions
        )
        sessions_html = (
            f'<ul style="list-style:none;padding:0">{rows}</ul>'
            '<form method="post" action="/account/sessions/revoke-all" style="margin-top:.5rem">'
            '<button type="submit" style="padding:.5rem 1rem">Sign out of all apps</button></form>'
        )
    else:
        sessions_html = '<p style="color:#666">No active app sessions.</p>'

    return f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Your account — Dragonfly</title></head>
<body style="font-family:system-ui;max-width:420px;margin:6vh auto;padding:0 1rem">
<h1 style="font-size:1.3rem">Your Dragonfly account</h1>
<p style="color:#666;margin-top:-.5rem">{escape(user.email)}</p>
{notice_html}{error_html}

<h2 style="font-size:1.05rem;margin-top:1.5rem">Change password</h2>
<form method="post" action="/account/password">
<label>Current password<br><input name="current_password" type="password" required style="width:100%;padding:.5rem"></label><br><br>
<label>New password<br><input name="new_password" type="password" required minlength="8" style="width:100%;padding:.5rem"></label><br><br>
<label>Confirm new password<br><input name="confirm_password" type="password" required minlength="8" style="width:100%;padding:.5rem"></label><br><br>
<button type="submit" style="padding:.6rem 1rem">Update password</button>
</form>

<h2 style="font-size:1.05rem;margin-top:1.75rem">Active sessions</h2>
<p style="color:#666;margin-top:-.25rem"><small>Each app you've signed into with Dragonfly. Revoke one if a device is lost.</small></p>
{sessions_html}

<p style="margin-top:1.75rem"><form method="post" action="/logout" style="display:inline"><button type="submit" style="padding:.4rem .8rem">Sign out of this page</button></form></p>
</body></html>"""


@router.get("/account", response_class=HTMLResponse)
async def account_page(request: Request, db: DbDep):
    user = await _session_user(request, db)
    if user is None:
        return RedirectResponse("/login?next=/account", status_code=status.HTTP_303_SEE_OTHER)
    sessions = await _active_sessions(db, user.id)
    return HTMLResponse(_page(user, sessions))


@router.post("/account/password")
@limiter.limit("5/minute")
async def change_password(
    request: Request,
    db: DbDep,
    current_password: Annotated[str, Form()],
    new_password: Annotated[str, Form()],
    confirm_password: Annotated[str, Form()],
):
    user = await _session_user(request, db)
    if user is None:
        return RedirectResponse("/login?next=/account", status_code=status.HTTP_303_SEE_OTHER)

    sessions = await _active_sessions(db, user.id)
    if not verify_password(current_password, user.password_hash):
        return HTMLResponse(
            _page(user, sessions, error="Current password is incorrect."), status_code=400
        )
    if new_password != confirm_password:
        return HTMLResponse(
            _page(user, sessions, error="New passwords do not match."), status_code=400
        )
    if len(new_password) < 8:
        return HTMLResponse(
            _page(user, sessions, error="New password must be at least 8 characters."),
            status_code=400,
        )

    user.password_hash = hash_password(new_password)
    await db.commit()
    return HTMLResponse(_page(user, sessions, notice="Password updated."))


@router.post("/account/sessions/revoke")
async def revoke_session(
    request: Request,
    db: DbDep,
    session_id: Annotated[str, Form()],
):
    user = await _session_user(request, db)
    if user is None:
        return RedirectResponse("/login?next=/account", status_code=status.HTTP_303_SEE_OTHER)

    result = await db.execute(
        select(RefreshToken).where(RefreshToken.id == session_id, RefreshToken.user_id == user.id)
    )
    token = result.scalar_one_or_none()
    notice = "Session revoked." if token else "That session no longer exists."
    if token and not token.revoked:
        token.revoked = True
        await db.commit()
    return HTMLResponse(_page(user, await _active_sessions(db, user.id), notice=notice))


@router.post("/account/sessions/revoke-all")
async def revoke_all_sessions(request: Request, db: DbDep):
    user = await _session_user(request, db)
    if user is None:
        return RedirectResponse("/login?next=/account", status_code=status.HTTP_303_SEE_OTHER)

    sessions = await _active_sessions(db, user.id)
    for token in sessions:
        token.revoked = True
    if sessions:
        await db.commit()
    return HTMLResponse(_page(user, [], notice=f"Signed out of {len(sessions)} app(s)."))
