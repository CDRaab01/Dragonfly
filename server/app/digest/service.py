"""Generate, store, and serve the weekly digest (ROADMAP3 Tier W1)."""

import datetime
import json
import logging

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.digest.aggregator import aggregate_week
from app.digest.narrator import narrate
from app.models.digest import WeeklyDigest, _now

logger = logging.getLogger("digest.service")


def week_bounds(today: datetime.date) -> tuple[datetime.date, datetime.date]:
    """The most recently completed Mon–Sun week (this week when today is Sunday)."""
    days_since_sunday = (today.weekday() + 1) % 7  # Mon=1 … Sun=0
    end = today - datetime.timedelta(days=days_since_sunday)  # the Sunday
    start = end - datetime.timedelta(days=6)  # that Monday
    return start, end


async def generate_and_store(
    db: AsyncSession, *, today: datetime.date | None = None, push: bool = True
) -> WeeklyDigest:
    today = today or datetime.date.today()
    start, end = week_bounds(today)
    domains = await aggregate_week(start, end)
    narrative = await narrate(domains)

    existing = (
        await db.execute(
            select(WeeklyDigest).where(
                WeeklyDigest.owner_email == settings.digest_owner_email,
                WeeklyDigest.week_start == start.isoformat(),
            )
        )
    ).scalar_one_or_none()
    if existing is not None:
        existing.data_json = json.dumps(domains)
        existing.narrative = narrative
        existing.week_end = end.isoformat()
        existing.generated_at = _now()
        digest = existing
    else:
        digest = WeeklyDigest(
            owner_email=settings.digest_owner_email,
            week_start=start.isoformat(),
            week_end=end.isoformat(),
            data_json=json.dumps(domains),
            narrative=narrative,
        )
        db.add(digest)
    await db.commit()
    await db.refresh(digest)
    if push:
        await _push_nudge()
    return digest


async def latest(db: AsyncSession) -> WeeklyDigest | None:
    return (
        await db.execute(
            select(WeeklyDigest)
            .where(WeeklyDigest.owner_email == settings.digest_owner_email)
            .order_by(WeeklyDigest.week_start.desc())
            .limit(1)
        )
    ).scalar_one_or_none()


async def _push_nudge() -> None:
    if not settings.ntfy_base_url or not settings.ntfy_digest_topic:
        return
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            await client.post(
                f"{settings.ntfy_base_url.rstrip('/')}/{settings.ntfy_digest_topic}",
                content="Your week is ready — tap to see your suite recap.".encode(),
                headers={"Title": "Your week in review", "Tags": "sparkles"},
            )
    except Exception:
        logger.warning("digest ntfy nudge failed (non-fatal)", exc_info=True)
