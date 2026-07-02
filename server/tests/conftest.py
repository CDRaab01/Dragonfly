import os

# Minimal env so `app` imports without a real deployment. /health and /version don't touch the DB,
# and the async engine is created lazily, so a placeholder URL is fine for these tests.
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://id:id@127.0.0.1:5432/id_test")
os.environ.setdefault("SECRET_KEY", "test-secret-not-for-production")
os.environ.setdefault("DB_NULLPOOL", "true")
