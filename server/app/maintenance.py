"""Housekeeping for the identity store (dragonfly-id hardening #3).

Refresh tokens accumulate dead rows: every rotation revokes the prior token, and the self-service
account page revokes them on demand. Neither a revoked nor an expired token can be exchanged (the
/token path rejects both), so once a token is revoked-or-expired it has no further use and can be
deleted. This keeps the table to "live sessions + not-yet-expired" without a separate scheduler —
it runs on startup (i.e. each deploy), which is frequent enough for a personal server.
"""

from datetime import datetime, timezone

from sqlalchemy import delete, or_
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.oauth import AuthorizationCode, RefreshToken


async def prune_stale_tokens(db: AsyncSession) -> int:
    """Delete refresh tokens that are revoked or expired, plus expired auth codes. Returns rows removed."""
    now = datetime.now(timezone.utc)
    refresh_result = await db.execute(
        delete(RefreshToken).where(
            or_(RefreshToken.revoked == True, RefreshToken.expires_at < now)  # noqa: E712
        )
    )
    # Auth codes are single-use + 60s-lived and normally self-delete on redemption, but an
    # abandoned /authorize leaves one behind — sweep expired ones too.
    code_result = await db.execute(
        delete(AuthorizationCode).where(AuthorizationCode.expires_at < now)
    )
    await db.commit()
    return (refresh_result.rowcount or 0) + (code_result.rowcount or 0)
