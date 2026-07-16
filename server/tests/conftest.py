import asyncio
import os

# Must be set before any `app.*` import (the engine is built at app.database import time).
os.environ.setdefault(
    "DATABASE_URL", "postgresql+asyncpg://spotter:spotter@127.0.0.1:5432/dragonfly_id_test"
)
os.environ.setdefault("SECRET_KEY", "test-secret-not-for-production")
os.environ.setdefault("DB_NULLPOOL", "true")
# https_only session cookies (hsts_enabled) get dropped over the http test client, which loses the
# login session before /authorize. Force it off for tests (the live .env carries HSTS_ENABLED=true).
os.environ.setdefault("HSTS_ENABLED", "false")
# Enable the cross-app service-token endpoint with a known test client (ROADMAP T2 #5).
os.environ.setdefault("CROSS_APP_CLIENTS", "testclient:testsecret")
# Enable the synthetic-smoke token endpoint with a known test client (Magpie CLAUDE.md §9).
os.environ.setdefault("SMOKE_CLIENTS", "smoketestclient:smoketestsecret")
# F2: the smoke subject-email allowlist (fail-closed) — the emails the endpoint tests mint for.
os.environ.setdefault("SMOKE_SUBJECT_EMAILS", "smoke@example.com u@e.com")
# Enable the weekly digest (Tier W1) with a known read key + owner for the digest tests.
os.environ.setdefault("DIGEST_READ_KEY", "test-digest-key")
os.environ.setdefault("DIGEST_OWNER_EMAIL", "owner@example.com")

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

import app.models.digest  # noqa: F401
import app.models.oauth  # noqa: F401  register tables on Base
import app.models.user  # noqa: F401
from app.database import Base, engine
from app.config import settings
from app.limiter import limiter
from app.main import app

limiter.enabled = False
# Invite gating is a deploy concern, not under test; disable it so the suite runs regardless of a
# local server/.env that carries REGISTRATION_INVITE_CODE (otherwise every /register call 403s).
settings.registration_invite_code = None


@pytest.fixture(scope="session")
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest_asyncio.fixture(scope="session", autouse=True)
async def setup_tables():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)
    yield
    await engine.dispose()


@pytest_asyncio.fixture
async def client():
    # Fresh cookie jar per test → no session unless the test logs in.
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver") as c:
        yield c
