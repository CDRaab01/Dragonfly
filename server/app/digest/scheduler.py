"""Weekly-digest scheduler (ROADMAP3 Tier W1).

An hourly check that, on Sunday evening, generates this week's digest once (idempotent — it won't
regenerate a week it already has). No-ops entirely when the feature is unconfigured, so it is safe
to always start in the app lifespan.
"""

import asyncio
import datetime
import logging

from app.config import settings
from app.database import AsyncSessionLocal
from app.digest.service import generate_and_store, latest, week_bounds

logger = logging.getLogger("digest.scheduler")

_FIRE_HOUR = 18  # Sunday 6pm local (the "Sunday ritual" slot)
_TICK_SECONDS = 3600


async def digest_scheduler_loop() -> None:
    if not settings.digest_owner_email:
        return  # feature off — don't spin
    while True:
        try:
            today = datetime.date.today()
            if today.weekday() == 6 and datetime.datetime.now().hour >= _FIRE_HOUR:
                start, _ = week_bounds(today)
                async with AsyncSessionLocal() as db:
                    current = await latest(db)
                    if current is None or current.week_start != start.isoformat():
                        await generate_and_store(db, today=today)
                        logger.info("weekly digest generated for week of %s", start.isoformat())
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning("digest scheduler tick failed (non-fatal)", exc_info=True)
        await asyncio.sleep(_TICK_SECONDS)
