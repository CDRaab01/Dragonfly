# ARCHITECTURE.md — Dragonfly (software-level)

How this repo is organized and why. Suite-level context: `C:\Code\ARCHITECTURE.md`. Working
instructions: [CLAUDE.md](CLAUDE.md). Plan-of-record docs: [BROKER.md](BROKER.md) (identity/config
broker), [CROSS-APP.md](CROSS-APP.md) (inter-app data links). Backlog: [ROADMAP.md](ROADMAP.md).

One repo, **two deliverables** — deliberately kept together (shared release/deploy plumbing,
zero friction to date):

1. `android/` — the **hub app** (`com.dragonfly`): suite launcher, update channel, config broker,
   status dashboard.
2. `server/` — **dragonfly-id**, the suite's OIDC identity server, live at
   https://id.dragonflymedia.org (:8004 / pg :5435). This is the suite's **root of trust**.

**Naming trap:** "Dragonfly" is also the Windows host and the media stack's brand. In code:
`dragonfly` = the app; `server`/dragonfly-id = the backend.

## server/ — dragonfly-id

Small on purpose (8 test files, 2 migrations): an identity server earns trust by
staying auditable.

### Module map

| Module | Responsibility |
|---|---|
| `app/oidc/keys.py` | RS256 signing key(s) + JWKS. Active signer + optional **secondary verify-only key** (`OIDC_SECONDARY_PUBLIC_KEY`/`_KEY_ID`) for zero-downtime rotation; boot-time guards reject half-set/kid-colliding config |
| `app/oidc/tokens.py` | Token mint/verify (Authlib JOSE). Access tokens `aud=suite` (~15 min), id_tokens `aud=<client_id>`+nonce, rotating refresh tokens |
| `app/oidc/clients.py` | Static public clients: `spotter`, `plate`, `cookbook`, `dragonfly`, `magpie` (+ `localdev`); redirect URIs `<package>:/oauth2redirect`; PKCE S256 mandatory, exact redirect match |
| `app/oidc/service_clients.py` | Confidential clients for cross-app tokens (`CROSS_APP_CLIENTS` env, `client_id:secret` list) and for synthetic-smoke tokens (`SMOKE_CLIENTS`, same list shape, deliberately a separate dict so one credential type can never mint the other's token) |
| `app/routers/oidc.py` | `/.well-known/*`, `/authorize` (session-gated → `/login` HTML form), `/token` (single-use 60 s codes), `/userinfo`, `/login`, `/logout` |
| `app/routers/accounts.py` | `/register` (invite-gated via `REGISTRATION_INVITE_CODE` — closed in prod) + `/login`/`/logout` HTML session surface |
| `app/routers/account.py` | Self-service (session-cookie-gated): `GET /account` page, `POST /account/password` (change), `POST /account/sessions/revoke[-all]` — active-session list + revoke, referenced by the refresh token's surrogate `id` (never its value); revoke leans on `/token` already rejecting `revoked` tokens |
| `app/routers/cross_app.py` | `POST /cross-app/token` — client-credentials → short-lived RS256 token `aud="cross-app"`; 404 until `CROSS_APP_CLIENTS` is set |
| `app/routers/smoke.py` | `POST /smoke/token` — client-credentials → short-lived RS256 token `aud="suite"` for an **allowlisted** throwaway email (Magpie CLAUDE.md §9: SSO-only apps have no register/login to script a post-deploy smoke against); 404 until `SMOKE_CLIENTS` is set; the subject must be in `SMOKE_SUBJECT_EMAILS` (fail-closed, F2) else 403 — without the allowlist a valid smoke credential could impersonate any account on any suite app |
| `app/models/oauth.py`, `user.py` | Auth codes, refresh tokens (with a surrogate `id` for revoke handles), users |
| `app/maintenance.py` | `prune_stale_tokens` — deletes revoked/expired refresh tokens + expired auth codes; run on startup via a defensive FastAPI lifespan hook (self-cleans each deploy, no scheduler) |

### Config gotchas (each has bitten)

- `ISSUER` must exactly equal the public URL — it's baked into every token; changing it breaks
  verification in every app server simultaneously.
- `OIDC_PRIVATE_KEY` is a single line with `\n` escapes; `server/.env` must be LF-ended.
- Key rotation runbook: `server/DEPLOY.md` "Rotating the OIDC signing key" (app servers
  force-refetch JWKS on unknown `kid`, so cutover is seamless).
- Losing this database strands every app's SSO link — it's in the nightly backup set; treat it
  as the most important data on the host.

## android/ — the hub app

### Package map (each package = one concern; `update/` and `status/` are the two pipelines)

| Package | Responsibility |
|---|---|
| `registry/` | `AppRegistry` — the six managed apps (key/package/repo), single source of truth. Every package here must also be in the manifest `<queries>` block or version reads silently fail |
| `settings/` | DataStore settings (auto-check interval, Wi-Fi-only, per-app broker server URL, digest base URL) + `PatStore` (GitHub PAT) and `DigestKeyStore` (weekly-digest read key) — both EncryptedSharedPreferences (`secrets` file); the PAT attaches only to `api.github.com`, the digest key rides the `X-Digest-Key` header |
| `net/` | Retrofit `GitHubApi`, `HttpFetcher` (version.json GETs), `NetworkStatus` |
| `digest/` | The weekly recap (client-only): `WeeklyDigest` DTOs (every field nullable — any domain null = that app was unreachable; narrative null = LM down), `DigestFormatter` (pure, unit-tested: headline strings, signed dollar formatting, week-range label, which-cards-show), `DigestRepository` (raw status-code-aware OkHttp GET `{digestBaseUrl}/digest/weekly` → `DigestResult` Success/NotYet(404)/NeedsKey(401)/Error). Fixed contract served by dragonfly-id |
| `update/` | The update pipeline: `ReleaseResolver` (pure, unit-tested: parse `version.json`, pick assets, diff versionCodes), `UpdateRepository`, `UpdateFlowManager` (download → verify → install phases), `InstalledAppsDataSource` |
| `install/` | `ApkDownloader` (OkHttp streaming; **SHA-256 gate deletes on mismatch — mandatory**), `ApkInstaller` (PackageInstaller session → system prompt), `InstallResultReceiver`/`InstallEventBus` |
| `history/` | Update history (JSON in DataStore, capped 50; Room deliberately rejected) |
| `work/` | `UpdateCheckWorker` (@HiltWorker periodic) + `UpdateScheduler`; WorkManager default initializer is removed for Hilt — don't re-add |
| `suiteconfig/` | `SuiteConfigProvider` — signature-permission ContentProvider (authority `com.dragonfly.suiteconfig`) serving per-app server URLs to siblings; the security model is the shared signing key |
| `status/` | The v2 dashboard: `ServiceRegistry` (**backends**, deliberately separate from AppRegistry — services ≠ installable apps), `StatusResolver` (pure), `StatusProber` (parallel fan-out on a dedicated 6s-timeout OkHttp client), `StatusRepository` (shared StateFlow). SUITE probes require `/health` ok + parse `/version`; REACHABILITY probes count any non-gateway HTTP (a Caddy basic_auth 401 = up); tailnet-only hosts degrade to "off-network", never a false "down". `StatusRepository.refresh()` also persists a compact `WidgetStatusSnapshot` (`StatusSnapshotStore`, DataStore) and pokes `WidgetRefresher` so the home-screen widget shows last-known truth without probing |
| `widget/` | `DragonflyWidget` — Glance home-screen "suite at a glance" (each suite app + a status dot; media rows dropped), reads the persisted `StatusSnapshotStore` via a Hilt `@EntryPoint` (Magpie/Cookbook widget precedent — the widget never runs network). `WidgetRefresher.updateAll` redraws after a probe pass; colors are hardcoded PULSE-violet (Glance can't read the Compose theme) |
| `ui/` | Home (app cards + status banner + "Your week" digest banner), detail, settings, status screen, digest screen (`Routes.DIGEST`); `DragonflyTheme` — violet leads |

### The pure/impure split (house pattern)

Both pipelines isolate decision logic in a pure, exhaustively unit-tested resolver
(`ReleaseResolver`, `StatusResolver`) fed by thin I/O shells. Extend by adding cases to the
resolver + tests first, then wire the shell. Unit tests: 4 files, JVM-only; the
PackageInstaller/notification flows need a phone — build-log entries state what was and wasn't
device-verified. Be equally honest in yours.

### Update-flow rules that protect the fleet

- versionCode (epoch minutes) is the only comparison field; `versionName`/tags are display-only.
- A release without `version.json` surfaces as "fix the release", never guessed from the tag.
- SHA-256 verification is mandatory (the GitHub release's `version.json` publishes the hash).
- No silent installs — one system-prompt tap per update is the accepted cost (rejected:
  device-owner/root).

## Invariants

1. dragonfly-id changes ship only with the rotation/consistency guards intact (ISSUER pin,
   kid checks, PKCE, single-use codes). When in doubt, add a test to the 6 rotation tests.
2. The signature-permission provider + SSO both assume the one suite signing key
   (`C:\Code\CLAUDE.md` invariant #1).
3. Registration stays invite-gated; accounts link by email (that's the trust boundary — see the
   suite ARCHITECTURE.md §5).
4. Every managed package: registry entry + `<queries>` entry + release workflow publishing
   `version.json`.
5. The hub must degrade gracefully when any backend is down — it is the thing you look at
   *during* an outage.
