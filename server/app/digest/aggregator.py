"""Pull the owner's week from each suite app (ROADMAP3 Tier W1).

The digest service lives inside dragonfly-id, so it mints its own RS256 cross-app token in-process
(no client secret) for the owner's email, then GETs each app's range endpoint with it. Every fetch
is best-effort: an unconfigured base URL or any failure yields ``None`` for that domain and the
digest degrades to whatever it could reach — one app being down never sinks the whole thing.
"""

import datetime
import logging

import httpx

from app.config import settings
from app.oidc import tokens

logger = logging.getLogger("digest.aggregator")

# (domain key, base-url setting attr, path) — the four suite reads.
_SOURCES = (
    ("training", "spotter_base_url", "/workouts"),
    ("nutrition", "plate_base_url", "/cross-app/summary"),
    ("cooking", "cookbook_base_url", "/cross-app/cooked"),
    ("money", "magpie_base_url", "/cross-app/summary"),
)


async def _fetch(client: httpx.AsyncClient, base_url: str, path: str, token: str, params: dict):
    if not base_url:
        return None
    try:
        r = await client.get(
            f"{base_url.rstrip('/')}{path}",
            params=params,
            headers={"Authorization": f"Bearer {token}"},
        )
        if r.status_code == 200:
            return r.json()
        logger.warning("digest source %s returned %s", path, r.status_code)
    except Exception:
        logger.warning("digest source %s unreachable", path, exc_info=True)
    return None


async def aggregate_week(start: datetime.date, end: datetime.date) -> dict:
    """The owner's week per domain: ``{training, nutrition, cooking, money}``, each the raw range
    response or ``None``. The caller narrates + stores it."""
    owner = settings.digest_owner_email
    token = tokens.mint_cross_app_token(email=owner, azp=settings.digest_client_azp)
    params = {"start": start.isoformat(), "end": end.isoformat()}
    domains: dict = {}
    async with httpx.AsyncClient(timeout=8.0) as client:
        for key, attr, path in _SOURCES:
            domains[key] = await _fetch(client, getattr(settings, attr), path, token, params)
    return domains
