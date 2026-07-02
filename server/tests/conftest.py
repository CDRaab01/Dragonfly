import asyncio
import os

# Must be set before any `app.*` import (the engine is built at app.database import time).
os.environ.setdefault(
    "DATABASE_URL", "postgresql+asyncpg://spotter:spotter@127.0.0.1:5432/dragonfly_id_test"
)
os.environ.setdefault("SECRET_KEY", "test-secret-not-for-production")
os.environ.setdefault("DB_NULLPOOL", "true")

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

import app.models.oauth  # noqa: F401  register tables on Base
import app.models.user  # noqa: F401
from app.database import Base, engine
from app.limiter import limiter
from app.main import app

limiter.enabled = False


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
