from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    # Signs the server's own session/refresh cookies + short-lived internal state (NOT the OIDC
    # tokens, which are RS256-signed with the key below). Keep secret; rotate freely.
    secret_key: str

    # --- OIDC issuer identity ---
    # The public base URL clients see as `iss` (e.g. https://id.dragonflymedia.org). Must match
    # what app servers validate. Defaults to localhost for dev.
    issuer: str = "http://localhost:8010"
    # PEM-encoded RS256 private key used to sign OIDC tokens; its public half is published at
    # /.well-known/jwks.json so app servers validate signatures without any shared secret. Unset
    # in dev ⇒ a throwaway key is generated on boot (fine locally, NOT for a real deploy — a
    # restart would invalidate live tokens). Generate a stable key for deployment.
    oidc_private_key: str | None = None
    oidc_key_id: str = "dragonfly-id-1"
    access_token_expire_minutes: int = 15
    refresh_token_expire_days: int = 30

    # Test-suite escape hatch (see database.py / Cookbook's note): NullPool avoids binding pooled
    # asyncpg connections to a dead per-test event loop.
    db_nullpool: bool = False

    # When set, registration requires a matching invite code — for a single-user suite this stays
    # set (or registration is closed after the one account exists).
    registration_invite_code: str | None = None
    # Trust X-Forwarded-For / CF-Connecting-IP for the rate-limit key. Only behind a trusted proxy.
    trust_proxy: bool = False
    # Emit HSTS. Enable only when served over HTTPS (TLS at the edge/proxy).
    hsts_enabled: bool = False
    # Expose interactive docs. Disable on public deploys.
    docs_enabled: bool = True

    # Deploy stamp surfaced by GET /version (injected at deploy time).
    git_sha: str = "unknown"
    built_at: str = "unknown"


settings = Settings()
