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
    # Optional SECOND key published in JWKS but NEVER used to sign — the seam for a zero-downtime
    # signing-key rotation. During a rotation you either pre-publish the incoming key here before
    # cutting `OIDC_PRIVATE_KEY` over to it, or keep the outgoing key published here until the last
    # token it signed has expired. Public-key PEM only (single line with \n escapes, like
    # OIDC_PRIVATE_KEY); its kid must differ from OIDC_KEY_ID; set both vars or neither. Full
    # procedure: server/DEPLOY.md "Rotating the OIDC signing key".
    oidc_secondary_public_key: str | None = None
    oidc_secondary_key_id: str | None = None
    access_token_expire_minutes: int = 15
    refresh_token_expire_days: int = 30

    # --- Cross-app service tokens (ROADMAP T2 #5) ---
    # Confidential clients allowed to request an RS256 cross-app token (aud="cross-app") from
    # POST /cross-app/token, replacing the suite's shared symmetric CROSS_APP_SECRET. Format is a
    # comma/whitespace-separated list of `client_id:secret` pairs, e.g.
    # "plate:s3cret1,cookbook:s3cret2,spotter:s3cret3". Empty ⇒ the endpoint is disabled (404).
    # Secrets are compared in constant time; keep them in .env (secret), one per calling server.
    cross_app_clients: str = ""
    # Cross-app tokens are used for a single immediate server-to-server call — keep them short.
    cross_app_token_expire_seconds: int = 120

    # --- Synthetic-smoke tokens (Magpie CLAUDE.md §9 — SSO-only apps have no register/login to
    # script a post-deploy smoke test against). Same `client_id:secret` list shape as
    # cross_app_clients, but POST /smoke/token mints an aud="suite" token directly (not
    # aud="cross-app") for a caller-supplied throwaway email — small, auditable, and revocable
    # independently of the real OIDC client list. Empty ⇒ the endpoint is disabled (404).
    smoke_clients: str = ""

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
