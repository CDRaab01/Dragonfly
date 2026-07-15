"""Token store housekeeping: prune revoked/expired refresh tokens + expired auth codes."""

from datetime import datetime, timedelta, timezone

import sqlalchemy as sa

from app.database import AsyncSessionLocal
from app.maintenance import prune_stale_tokens
from app.models.oauth import AuthorizationCode, RefreshToken

FUTURE = datetime.now(timezone.utc) + timedelta(days=30)
PAST = datetime.now(timezone.utc) - timedelta(days=1)


async def _wipe(db):
    await db.execute(sa.delete(RefreshToken))
    await db.execute(sa.delete(AuthorizationCode))
    await db.commit()


async def test_prune_removes_only_dead_rows(client):  # client fixture ensures tables exist
    async with AsyncSessionLocal() as db:
        await _wipe(db)  # isolate from rows other tests left in the session-scoped DB
        db.add_all(
            [
                # keep: live session (not revoked, not expired)
                RefreshToken(
                    token="live", id="id-live", user_id="u", client_id="spotter", expires_at=FUTURE
                ),
                # drop: revoked (still unexpired)
                RefreshToken(
                    token="revoked", id="id-rev", user_id="u", client_id="spotter",
                    revoked=True, expires_at=FUTURE,
                ),
                # drop: expired (not revoked)
                RefreshToken(
                    token="expired", id="id-exp", user_id="u", client_id="plate", expires_at=PAST
                ),
                # drop: expired auth code
                AuthorizationCode(
                    code="oldcode", client_id="spotter", redirect_uri="x", user_id="u",
                    code_challenge="c", expires_at=PAST,
                ),
            ]
        )
        await db.commit()

    async with AsyncSessionLocal() as db:
        removed = await prune_stale_tokens(db)
    assert removed == 3

    async with AsyncSessionLocal() as db:
        survivors = (await db.execute(sa.select(RefreshToken))).scalars().all()
    tokens = {s.token for s in survivors}
    assert tokens == {"live"}


async def test_prune_is_safe_on_empty_store(client):
    async with AsyncSessionLocal() as db:
        await _wipe(db)
        assert await prune_stale_tokens(db) == 0


async def test_lifespan_startup_prunes(client):
    """The app's startup hook runs the prune (and must never raise)."""
    from app.main import app, lifespan

    async with AsyncSessionLocal() as db:
        await _wipe(db)
        db.add(
            RefreshToken(
                token="dead", id="id-dead", user_id="u", client_id="spotter",
                revoked=True, expires_at=FUTURE,
            )
        )
        await db.commit()

    async with lifespan(app):  # enters = startup body ran
        pass

    async with AsyncSessionLocal() as db:
        remaining = (await db.execute(sa.select(RefreshToken))).scalars().all()
    assert remaining == []
