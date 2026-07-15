import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from starlette.middleware.sessions import SessionMiddleware

from app.config import settings
from app.database import AsyncSessionLocal
from app.limiter import limiter
from app.maintenance import prune_stale_tokens
from app.routers import account, accounts, cross_app, export, oidc, smoke

logger = logging.getLogger("dragonfly-id")

# Single source for the human-facing version, reused by GET /version below.
APP_VERSION = "0.1.0"


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Housekeeping on boot (each deploy): sweep revoked/expired tokens + stale auth codes.
    # Defensive — a maintenance hiccup must never stop the identity server from starting.
    try:
        async with AsyncSessionLocal() as db:
            removed = await prune_stale_tokens(db)
        if removed:
            logger.info("startup prune removed %d stale token/code rows", removed)
    except Exception:  # noqa: BLE001
        logger.warning("startup token prune failed (non-fatal)", exc_info=True)
    yield


app = FastAPI(
    title="Dragonfly ID",
    version=APP_VERSION,
    lifespan=lifespan,
    docs_url="/docs" if settings.docs_enabled else None,
    redoc_url="/redoc" if settings.docs_enabled else None,
    openapi_url="/openapi.json" if settings.docs_enabled else None,
)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# Browser/Custom-Tab session for the interactive login step of the auth-code flow. `https_only`
# tracks the HTTPS deploy flag so the cookie is Secure in production and usable over http locally.
app.add_middleware(
    SessionMiddleware,
    secret_key=settings.secret_key,
    https_only=settings.hsts_enabled,
    same_site="lax",
)

# CORS: bearer-token clients + the OIDC discovery/JWKS endpoints need to be broadly reachable;
# credentials off (incompatible with wildcard origins and unnecessary for token auth).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def security_headers(request: Request, call_next) -> Response:
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    if settings.hsts_enabled:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return response


app.include_router(accounts.router)
app.include_router(account.router)
app.include_router(oidc.router)
app.include_router(cross_app.router)
app.include_router(export.router)
app.include_router(smoke.router)


@app.get("/health", tags=["health"])
async def health() -> dict:
    return {"status": "ok"}


@app.get("/version", tags=["version"])
async def version() -> dict:
    # Unauthenticated (like /health) so clients can see what's running before login.
    return {
        "name": app.title,
        "version": APP_VERSION,
        "commit": settings.git_sha,
        "built_at": settings.built_at,
    }
