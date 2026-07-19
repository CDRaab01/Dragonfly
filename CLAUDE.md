# CLAUDE.md — Dragonfly (Suite Hub)

## Purpose
**Dragonfly** is the central hub app for the personal Android app suite. Three jobs:
1. **Launcher/dashboard** — home surface with cards for the sibling apps (**Spotter**, **Plate**, **Cookbook**, **Hawksnest**): launch installed apps, see installed vs. latest version at a glance.
2. **Shared settings** for the suite.
3. **Update deployment channel** — sibling APKs update in-app instead of manually pulling from GitHub.

Since 2026-07-02 the repo has a **fourth job**: `server/` hosts **dragonfly-id**, the suite's OIDC
identity server (live at https://id.dragonflymedia.org). Dragonfly is the suite's **config &
identity broker** — the plan of record is [BROKER.md](BROKER.md); the as-built status ledger is
below ("Broker status"). Inter-app data links (rules, live surfaces, approved roadmap,
non-goals) are documented in [CROSS-APP.md](CROSS-APP.md).

> **Naming:** the app is *Dragonfly*; the self-hosted server (the Tailscale node) is referred to as *the Dragonfly server* below. Don't conflate them in code — use `dragonfly` for the app's package/module names and `server`/`selfHost` for the backend.

## Stack
- **Language:** Kotlin (native)
- **Min SDK:** 26 (API 26) — required for scoped `PackageInstaller` session flow
- **Target SDK:** 35
- **UI:** Jetpack Compose, Material 3
- **DI:** Hilt
- **Async:** Coroutines + Flow
- **Networking:** Retrofit + OkHttp + kotlinx.serialization
- **Background work:** WorkManager (periodic update checks per the auto-check interval setting)
- **Storage:** DataStore (Preferences) for settings; Room only if update history needs querying
- **Downloads:** OkHttp streaming to app cache/external files dir (not `DownloadManager` — it's unreliable over VPN interfaces like Tailscale)

## Install mechanism (decided)
Standard **system installer prompt** via `PackageInstaller` — no silent install.
- Requires `REQUEST_INSTALL_PACKAGES` permission (declared in manifest).
- Route users to grant "Install unknown apps" for Dragonfly on first update (deep-link to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`).
- Each update = one system-driven confirmation tap. Acceptable for personal sideload; silent install would need device owner (factory reset) or root — explicitly out of scope.

## Update source: GitHub Releases

The hub updates every app from **GitHub Releases** — the only source. (A configurable self-hosted
manifest source was designed but never stood up; it was removed 2026-07-15 per the "no dead
settings" 1.0 bar — "delete beats build". See ROADMAP "Road to 1.0" #2.)

- Poll `GET /repos/{owner}/{repo}/releases/latest` via GitHub REST API.
- **Each sibling's release must include a `version.json` asset** (uploaded by its release CI) alongside the `.apk`:
```json
{ "versionCode": 42, "versionName": "1.4.2", "sha256": "…", "minSdk": 26 }
```
  This is required because the GitHub API only exposes the semver tag — `versionCode` (the actual source of truth) has to be published explicitly. Note this requirement in each sibling's CLAUDE.md / release workflow.
- Parse `version.json` + the `.apk` asset URL; `tag_name` is display-only.
- Optional PAT stored in DataStore for private repos / rate limits.
- If a release lacks `version.json`, surface it as "unknown version — fix the release" rather than guessing from the tag.

## Version comparison
- Compare installed `versionCode` (via `PackageManager.getPackageInfo`) against source's `versionCode` — available from both backends now that GitHub releases ship `version.json`.
- `versionCode` is the source of truth; show `versionName` in UI.
- Sibling apps must ship monotonically increasing `versionCode` — note this in each sibling's build config.

## Update flow
1. Refresh: query each app's latest GitHub release (manual, on-launch, or WorkManager periodic per settings).
2. Diff against installed versions → mark "Update available" (notification if from background check).
3. On tap: download APK to app-scoped storage.
4. **Verify SHA-256** against the `version.json` value — mandatory.
5. Launch `PackageInstaller` session → system prompt → install.
6. Record result (success/version/timestamp) in update history.

## Settings (shared)
DataStore-backed, exposed to sibling apps as needed:
- GitHub PAT (encrypted — use `EncryptedSharedPreferences` or DataStore + Tink for secrets)
- Auto-check interval (manual / daily / on-launch)
- Wi-Fi-only downloads toggle
- **Sharing to siblings:** if siblings must read Dragonfly settings, expose via a signature-permission `ContentProvider` (all apps signed with the same key). Document the shared permission string here once chosen.

## Theming — PULSE via the shared library (as built)
The shared design-token library **already exists**: the sibling `C:\Code\Pulse` repo publishes
`design.pulse:pulse-ui` (theme tokens + component kit), consumed as a Gradle **composite build**
(`includeBuild("../../Pulse")` — Cookbook precedent). Dragonfly does NOT hand-port CSS values;
the original plan to copy Hawksnest's `themes.css` is superseded.

- `ui/theme/DragonflyTheme.kt` wraps `PulseTheme(accent = PulseAccent.Violet)` — **violet leads**
  (Spotter/Plate lead blue, Cookbook amber; violet was unclaimed and reads "system").
- Channel semantics (Dragonfly's own layer, `DragonflyColors`): **hub** violet = identity/primary
  actions, **info** blue = version readouts, **ok** green = up-to-date/success,
  **warn** amber = update available/attention.
- Keep AGP/Kotlin/Compose versions aligned with Pulse's `gradle/libs.versions.toml` — composite
  builds only stay binary-compatible when they match (currently AGP 9.1.1 / Kotlin 2.2.10 /
  BOM 2026.06.01).

## Permissions (manifest)
- `INTERNET`
- `REQUEST_INSTALL_PACKAGES`
- `ACCESS_NETWORK_STATE` (Wi-Fi-only gating, reachability)
- `POST_NOTIFICATIONS` (API 33+, for background "update available" nudges)

## Screens
- **Home (launcher/dashboard):** app cards (Spotter / Plate / Cookbook / Hawksnest) — tap to launch the installed app; each card shows installed version, latest version, status, and a per-app update button when one is available. Global "Check all". Cards for not-yet-installed apps offer install. A **suite-status banner** ("All systems go" / "N down") sits below the hero and taps through to Suite status, and a **"Your week" digest banner** taps through to the weekly digest.
- **Suite status (v2 dashboard):** live "is my world green" surface — per-service up/down for the 4 suite backends (with version·commit + last-deploy from `/version`) and the media stack (reachability only), grouped Suite / Media / Automation. See the v2 build log below.
- **Weekly digest ("Your week"):** the narrated suite recap (client-only) fetched from dragonfly-id (`GET {digestBaseUrl}/digest/weekly`, `X-Digest-Key`) — a violet narrative paragraph + four optional Training/Nutrition/Cooking/Money cards (each shows only when its domain returned data). Empty states: 404 → "no digest yet", 401 → "add your key". See the 2026-07-16 build log below.
- **Home-screen widget:** a Glance "suite at a glance" grid (`widget/DragonflyWidget`) — each suite app + a status dot from the last persisted probe snapshot; never runs a network probe.
- **App detail:** version history, source config, changelog (GitHub release body if available).
- **Settings:** as above.

## App registry
Central list keyed by package name — single source of truth for what Dragonfly manages:
```kotlin
data class ManagedApp(
    val key: String,          // "spotter"
    val displayName: String,  // "Spotter"
    val packageName: String,  // "com.spotter"
    val githubRepo: String?,  // "owner/repo", null if self-host only
)
```
Registry as built (`registry/AppRegistry.kt`) — five apps, Dragonfly included (self-update):

| key | package | repo |
|---|---|---|
| spotter | `com.spotter` | CDRaab01/Spotter |
| plate | `com.plate` | CDRaab01/Plate |
| cookbook | `com.cookbook` | CDRaab01/Cookbook |
| hawksnest | `com.hawksnest` | CDRaab01/Hawksnest |
| magpie | `com.magpie` | CDRaab01/Magpie |
| dragonfly | `com.dragonfly` | CDRaab01/Dragonfly |

Every managed package must also appear in the manifest `<queries>` block (API 30+ package
visibility) or version reads and launch intents silently fail.

## Constraints / notes
- **Update `ARCHITECTURE.md` in the same PR** when a change alters architecture — a module's
  responsibility, a layer boundary, a cross-app/identity contract, or the data model. Silently-drifting
  docs are how Spotter's API docs said `/plans` for a round (ROADMAP2 T2 #5c).
- No Play Store; sideload-only suite. Do not add Play-specific APIs.
- SHA-256 verification is mandatory (the GitHub release's `version.json` publishes the hash).
- Keep Dragonfly itself updatable via the same flow (self-update path) — it lists itself as a managed app.
- The Dragonfly server may be down; every network path must degrade gracefully.

## Open items
- [x] ~~Confirm package names~~ — done, see the registry table.
- [x] ~~Decide if settings are shared to siblings~~ → YES: Dragonfly becomes the suite's
      config & identity broker. **Plan of record: [BROKER.md](BROKER.md)** (decided 2026-07-02:
      signature-permission ContentProvider + SSO in scope, SSO shape gated). Supersedes the two
      old open items below.
- [x] ~~Same signing key across suite?~~ → YES, required by the broker's signature permission;
      BROKER.md Phase 0 is the migration plan (secret suite key, guard-pin updates, one-time
      reinstall of all five apps).
- [x] ~~Set up Tailscale Serve / self-host manifest source~~ — **dropped 2026-07-15.** The
      self-host update source was never stood up; GitHub Releases proved sufficient, so the whole
      configurable-source machinery was removed ("delete beats build", ROADMAP Road-to-1.0 #2).
- [x] ~~`version.json` in releases~~ + [x] ~~automatic releases across the suite~~ — done
      2026-07-02. Every app now auto-publishes a signed GitHub Release (+ `version.json`:
      `{"versionCode", "versionName", "sha256", "minSdk"}`) on **any push to `main` that touches
      `android/**`** — shipping to devices is just merging to main. See "Release automation" below.
- [x] ~~Push to GitHub (CDRaab01/Dragonfly)~~ — done 2026-07-02; direct `git push` works from the
      host (`gh` CLI is not installed there).
- [x] ~~Set the `DRAGONFLY_DIR` Actions variable~~ — done 2026-07-03; push-to-deploy verified
      end-to-end (manual dispatch → runner `dragonfly` → redeploy.ps1 → health gate green,
      public OIDC discovery 200).
- [x] ~~Dashboard v2 candidates (out of v1 scope): live service status from the server stack
      (Plex, Cookbook server, etc.).~~ — **DONE 2026-07-04** (ROADMAP Tier 3 #1). Suite status
      dashboard shipped: `status/` package + `ui/status/`, banner on Home. See the v2 build log.
- [ ] Confirm Hawksnest's tailnet-reachable status URL. The dashboard registry currently guesses
      `http://dragonfly.tail2ce561.ts.net:30080`, which is unreachable even from the host (k3s runs
      in WSL; exposure is still open in hawksnest-automation), so that row shows "off-network"
      until the real URL is pinned. Neutral state, not a false outage.

---

## Release automation (suite-wide, 2026-07-02)

Every app in the suite auto-releases so Dragonfly's update channel stays current with zero manual
steps. **Trigger:** a push to `main` that changes `android/**` (or the release workflow itself).
A server-only, docs, or CI-only commit does **not** cut a release, so devices aren't nudged with
identical-code "updates".

**Each release run:** runs unit tests (gating) → builds a signed release APK (CI release key when
the `KEYSTORE_*` secrets are set, else the committed stable key) → derives the version → writes
`version.json` → publishes a GitHub Release.

**Versioning:** `versionCode` = **epoch minutes** (`$(( $(date +%s) / 60 ))`) — monotonic across
workflow changes and always far above any prior run-number-based code, so a fresh release never
reads as a downgrade to Android or the hub. (A per-workflow `github.run_number` was rejected: a
new workflow file resets it to 1, which would go *backwards* and collide with old tags.)
`versionName` = the app's `major.minor` from `build.gradle.kts` + the **commit count**
(`git rev-list --count HEAD`, so the release checkout uses `fetch-depth: 0`), e.g. Spotter
`1.1.132`, Cookbook `0.3.22`. Readable, monotonic, and clear of the old low-numbered tags. To bump
major/minor, edit the default `versionName` in `build.gradle.kts` (the workflow reads it via
locale-safe `sed`). versionCode and versionName are intentionally different counters — versionCode
is the machine-comparison field, versionName the human label (standard Android split).

**Workflow files:** `release.yml` in Spotter/Plate/Cookbook/Dragonfly; `android-release.yml` in
Hawksnest (tags `android-vX.Y.Z` to stay clear of its web `v*` releases). The old tag-triggered
release jobs were removed from the `ci.yml`s (now push/PR tests + debug build only). A
`workflow_dispatch` button remains on each as a manual escape hatch. Cookbook + Dragonfly release
jobs check out the sibling Pulse repo for the composite build.

## Build log (2026-07-02) — v0.1.0 built

v1 built in one pass: `:app:assembleDebug` + `:app:assembleRelease` + `:app:testDebugUnitTest`
green (11 JVM unit tests: ReleaseResolver parse/asset/state matrix, SHA-256 vectors,
SettingsSnapshot). Project lives at `android/` mirroring the Cookbook layout; Pulse consumed via
composite build.

**Architecture (package `com.dragonfly`):**
- `registry/` — `AppRegistry`, the five managed apps (see table above).
- `settings/` — `SettingsRepository` (DataStore: global + per-app source, self-host URL,
  interval, Wi-Fi-only) and `PatStore` (GitHub PAT in `EncryptedSharedPreferences`; sync reads
  so the OkHttp interceptor can attach it — `api.github.com` hosts only, never the self-host).
- `net/` — Retrofit `GitHubApi` (releases/latest), `HttpFetcher` (arbitrary-URL GETs:
  manifest.json, version.json assets), `NetworkStatus` (unmetered check).
  With a PAT, asset downloads switch to the API asset endpoint + `Accept: octet-stream`
  (private-repo support); without one, `browser_download_url`.
- `update/` — `ReleaseResolver` (pure, unit-tested: parsing/asset selection/version diff),
  `UpdateRepository` (per-app source resolution; one manifest fetch per refresh; self-host
  failure falls back to GitHub with a note), `UpdateFlowManager` (app-scoped download → verify →
  install pipeline with per-app phase/progress, shared by Home + Detail VMs),
  `InstalledAppsDataSource` (PackageManager versions + launch intents).
- `install/` — `ApkDownloader` (OkHttp streaming to cache; SHA-256 gate deletes on mismatch),
  `ApkInstaller` (PackageInstaller session → system prompt; unknown-sources deep link),
  `InstallResultReceiver` (relays STATUS_PENDING_USER_ACTION, records terminal results),
  `InstallEventBus` (results → UI).
- `history/` — `UpdateHistoryStore` (JSON list in DataStore, capped 50; Room deliberately out).
- `work/` — `UpdateCheckWorker` (@HiltWorker, DAILY periodic; notification when updates found)
  + `UpdateScheduler` (aligns WorkManager with settings; UNMETERED constraint when Wi-Fi-only).
- `ui/` — `DragonflyTheme` (violet), Home (hero header + app cards: status pill,
  installed → latest mono readout, Update/Install/Open, pipeline progress), App detail
  (version panel, source override chips, changelog, history), Settings (source, self-host URL,
  PAT, auto-check, Wi-Fi-only, About).

**Verification honesty:** built + unit-tested only — no device/emulator pass yet. The
PackageInstaller prompt flow, notification tap-through, and a real GitHub release check need a
phone (and the sibling release workflows don't publish `version.json` yet, so every GitHub check
will correctly report "release missing version.json" until those ship).

**Gotchas encoded in the build:**
- WorkManager default initializer removed in the manifest (Hilt worker factory) — don't re-add.
- `<queries>` block is load-bearing (see registry section).
- The installer result PendingIntent must be `FLAG_MUTABLE` (system appends status extras).
- AGP 8.5.0 warns about compileSdk 35; suite-wide known noise, ignore (Cookbook does).

## Build log (2026-07-04) — v2 live status dashboard (ROADMAP Tier 3 #1)

A glanceable "is my world green" surface: a status banner on Home → a **Suite status** screen.
`:app:testDebugUnitTest` (new `StatusResolverTest`, 10/10) + `:app:assembleDebug` green; the 4
suite `/health`+`/version` and the 6 media endpoints were live-probed during the build. On-device
render/tap-through deferred (needs the phone).

**Architecture (package `com.dragonfly.status`, mirrors `update/`):**
- `MonitoredService`/`ServiceRegistry` — watched **backends**, deliberately separate from
  `AppRegistry` (services ≠ installable apps: the `dragonfly` app's backend is `id.*`, Hawksnest
  has no public backend, the media stack has no app). Two probe types: **SUITE** (`/health` must be
  `{"status":"ok"}`, `/version` → version·commit·built_at) and **REACHABILITY** (any non-gateway
  HTTP response = up — Caddy basic_auth `401` counts, per OPERATIONS.md §3).
- `StatusResolver` — the pure, unit-tested half (classification, `/version` parse, relative time,
  Home-banner aggregate), the `ReleaseResolver` precedent.
- `StatusProber` — parallel fan-out on a **dedicated 6s-timeout OkHttp client** (`@Named("status")`
  in `NetworkModule`) so a dead host can't stall the dashboard; `StatusRepository` caches one shared
  `StateFlow` for the banner + screen.
- `ui/status/` — `SuiteStatusScreen` (grouped Suite/Media/Automation) + the Home banner; new
  `Routes.STATUS`.

**Notes / gotchas:**
- Tailnet-only services (Hawksnest) degrade to **"off-network"**, never a false "down".
- Home's status refresh runs in its own coroutine, independent of the (slower) GitHub update check.
- Media/tailnet probes are arbitrary GETs — **no `<queries>` entry** (that's package visibility).

## Build log (2026-07-15) — home-screen suite-status widget (Tier W4)

A Glance "suite at a glance" home-screen widget (`widget/DragonflyWidget`): each suite app + a
status dot (green up / red down / dim off-network·unknown), headed "Dragonfly" in the hub's violet,
tap → MainActivity. Media rows dropped so it stays the *app* glance. Mirrors the Magpie/Cookbook
Glance precedent — the widget **never probes**: `StatusRepository.refresh()` now persists a compact
`WidgetStatusSnapshot` (`StatusSnapshotStore`, DataStore) and pokes `WidgetRefresher.updateAll`
after every probe pass, and the widget reads that last-known snapshot via a Hilt `@EntryPoint`.
Colors are hardcoded PULSE-violet (Glance can't read the Compose theme). Glance/glance-material3
1.1.1 added to the catalog. `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green (new
`WidgetSnapshotTest`, 2/2); `assembleDebug` left to CI (local Pulse junction).

## Build log (2026-07-16) — suite weekly digest (Tier W1, the "one product" surface)

The flagship cross-app feature: a digest service **inside dragonfly-id** (`server/app/digest/`) plus
a hub screen. Server: a Sunday-evening scheduler → an aggregator that mints an in-process RS256
cross-app token for the owner and pulls Spotter/Plate/Cookbook/Magpie range endpoints (each
best-effort) → LM Studio narrates the numbers (degrades to numbers-only when unreachable) → one
`weekly_digests` row/week (migration `0003`) → an ntfy nudge. `GET /digest/weekly` +
`POST /digest/generate` are gated by `X-Digest-Key`. **Dormant until armed** (see "Arming the weekly
digest" above): the read/generate endpoints 404 and the scheduler no-ops until `DIGEST_OWNER_EMAIL`
+ `DIGEST_READ_KEY` (and the per-app base URLs) are set. 52 server tests green (new `test_digest.py`:
generate/read/auth/degrade + week-bounds math). Hub: `digest/` (nullable `WeeklyDigest` DTOs — any
domain null = that app was unreachable, narrative null = LM down; pure `DigestFormatter`;
status-code-aware `DigestRepository`), `ui/digest/` DigestScreen ("Your week" — narrated paragraph +
four optional cards), a Home "Your week" banner, and a Settings "Weekly digest" section (server URL +
read key, `DigestKeyStore` encrypted). Hub gate green (`:app:compileDebugKotlin`
`:app:testDebugUnitTest`, new DTO-parse + formatter tests). Deployed: Server CI + Deploy green on
`a20b5c1`; the server redeploy is a runtime no-op until the owner arms the `.env`.


---

## Broker status (as-built ledger for BROKER.md — updated 2026-07-03)

BROKER.md is the *plan*; this is what actually shipped. All phases below are **live in production**
unless marked otherwise.

- **Phase 0 — suite signing key: DONE.** All five apps (Spotter, Plate, Cookbook, Hawksnest,
  Dragonfly) sign with one secret suite key. Signer SHA-256 pin
  `5a596c9ea21bfaddae572a66514553c0f8c6db4ed796deabe4f56c9040c2cf8a` is enforced post-build by an
  `apksigner` guard step in every release workflow (a vanished `KEYSTORE_*` secret fails the build
  instead of silently publishing an APK signed with the wrong key). The keystore, its passwords,
  and rotation instructions live OUTSIDE all repos on the host (`~\.dragonfly-suite\`) — the repos
  are public and the signature permission is only as strong as the key's secrecy. Rotating the key
  means: new pin in all five workflows + one-time uninstall/reinstall of every app on the phone.
- **Phase 1 — config broker: DONE.** `SuiteConfigProvider` (signature-permission ContentProvider,
  authority `com.dragonfly.suiteconfig`, permission `com.dragonfly.permission.READ_SUITE_CONFIG`)
  serves per-app `server_base_url`/`selfhost_base_url`; Settings has a "Managed app servers" card.
  Spotter/Plate/Cookbook each carry a `util/SuiteConfigReader` that queries
  `content://com.dragonfly.suiteconfig/config/<appKey>` in `App.onCreate` (so a value change needs
  an app process restart to take effect) and falls back to local prefs when the hub is absent,
  denied, or blank. Hawksnest is deliberately excluded (it targets Home Assistant, not a suite
  backend). Verified on-device 2026-07-02.
- **Phase 2 — SSO: LIVE** for Cookbook, Spotter, and Plate (2a–2d complete; Hawksnest N/A — no
  suite backend to broker). Remaining optional: **2e** (retire per-app password endpoints).
  Sub-phase record: 2a identity server built+deployed; 2b `POST /auth/suite` live in all three app
  servers; 2c/2d AppAuth "Sign in with Dragonfly" shipped in all three Android clients and
  verified on-device. Accounts link **by email** — the identity email must match the app-server
  account email or a fresh account is created there.

## server/ — dragonfly-id (the identity server)

FastAPI OIDC provider, **live at https://id.dragonflymedia.org** from this repo's root
`docker-compose.yml` (host ports: API 8004, Postgres 5435 — 8000–8003/5432–5434 belong to the
sibling apps; cloudflared behind the `tunnel` profile). Python 3.12 (`server/.venv`), Alembic
migrate-on-boot, `server-ci.yml` (ruff + pytest + migration smoke test). Operator checklist:
[server/DEPLOY.md](server/DEPLOY.md).

- **Endpoints:** `/.well-known/openid-configuration`, `/.well-known/jwks.json`, `/authorize`
  (auth-code, **PKCE S256 mandatory**, strict exact redirect-URI match, session-gated → `/login`),
  `/token` (auth code + rotating refresh; codes single-use, 60 s), `/userinfo`, `/register`
  (invite-gated via `REGISTRATION_INVITE_CODE` — registration is closed in prod), `/login`
  (HTML form + session cookie), `/logout`, `GET /account` + `POST /account/password` +
  `POST /account/sessions/revoke[-all]` (session-cookie-gated self-service: password change +
  active-session list/revoke; sessions keyed by the refresh token's surrogate `id`),
  `POST /cross-app/token` (confidential
  client_credentials → short-lived RS256 cross-app token, `aud="cross-app"`; enabled by
  `CROSS_APP_CLIENTS`, disabled/404 when unset — ROADMAP T2 #5, see [CROSS-APP.md](CROSS-APP.md)).
- **Tokens:** RS256 via Authlib JOSE. Access tokens `aud=suite` (what app servers verify);
  id_tokens `aud=<client_id>` + nonce. Static public clients: `spotter`, `plate`, `cookbook`,
  `dragonfly`, `magpie`, `remnant`, plus `localdev` for tests. Redirect URIs are
  `<package>:/oauth2redirect`.
  **Synthetic-smoke tokens (2026-07-05, Magpie Phase 8/1):** `POST /smoke/token` mints an
  `aud=suite` token for a caller-supplied throwaway email via a confidential client credential
  (`SMOKE_CLIENTS` env, same `client_id:secret` list shape as `CROSS_APP_CLIENTS` but a
  deliberately separate dict — a smoke credential can never mint a cross-app token or vice
  versa). Exists because Magpie is SSO-only (no register/login) and needs *something* to mint
  a session token against for its post-deploy smoke test; 404 until `SMOKE_CLIENTS` is set.
  **The subject email is allowlisted (`SMOKE_SUBJECT_EMAILS`, fail-closed — F2, 2026-07-08):**
  even a valid smoke credential may only mint for a pre-designated throwaway email, else 403;
  an empty allowlist denies everyone. Without it the smoke secret was an impersonate-any-account
  oracle across every suite app. Non-secret ⇒ pinned in compose `environment:` (invariant #4),
  set to `magpie-smoke@dragonflymedia.org` + `remnant-smoke@dragonflymedia.org` (both SSO-only
  apps smoke via a suite token) to match each app's `synthetic_smoke.py`.
- **Weekly digest (Tier W1, 2026-07-16):** `server/app/digest/` assembles the owner's week across
  the suite and narrates it. `GET /digest/weekly` and `POST /digest/generate` are both gated by the
  `X-Digest-Key` header (owner-scoped single key — the hub has no user session); **404 until
  `DIGEST_READ_KEY` is set, 401 on a wrong key.** The aggregator mints an in-process RS256 cross-app
  token for the owner (no client secret) and pulls Spotter `/workouts`, Plate `/cross-app/summary`,
  Cookbook `/cross-app/cooked`, Magpie `/cross-app/summary` — each best-effort, so one app down never
  sinks the digest. LM Studio narrates the numbers (degrades to numbers-only when unreachable). One
  `weekly_digests` row per owner+week (migration `0003`); an hourly scheduler generates once on Sunday
  ≥18:00 local (idempotent), then fires an ntfy nudge. **Entirely dormant until armed** — see "Arming
  the weekly digest" below; smoke-test with `POST /digest/generate` (+ the read key header).
- **Config gotchas:** `ISSUER` must exactly equal the public URL (it is baked into every issued
  token); `OIDC_PRIVATE_KEY` is a single line with `\n` escapes; `server/.env` must be LF-ended.
  Changing ISSUER breaks verification in every app server until config is updated — coordinate with
  the apps' `SUITE_JWKS_URL`/`SUITE_ISSUER` pins. **Rotating the signing key is zero-downtime** and
  needs no app-side change: publish a second verify-only key in the JWKS
  (`OIDC_SECONDARY_PUBLIC_KEY`/`OIDC_SECONDARY_KEY_ID`), sign with the new one, retire the old after
  the ~15 min access-token tail expires — full runbook in [server/DEPLOY.md](server/DEPLOY.md)
  "Rotating the OIDC signing key" (the app servers force-refetch JWKS on an unknown `kid`, so the
  cutover is seamless).
- **App-server side (2b, same pattern in Spotter/Plate/Cookbook):** `POST /auth/suite` accepts a
  suite access token, validates it against the JWKS (aud/iss checked), find-or-creates the local
  user **by email** (new users get an unusable random password hash), returns the app's own
  session tokens. Feature-flagged: unset `SUITE_JWKS_URL`/`SUITE_ISSUER` ⇒ 404, password auth
  untouched. **Those two vars are pinned in each app's compose `environment:` block on purpose** —
  Docker Compose does not re-read changed `env_file` content on redeploy, and the flag silently
  vanishing (twice) was a production 404 regression. Keep non-secret required config in
  `environment:`, secrets in `.env`.
- **Android side (2c, same pattern in all three clients):** AppAuth (`net.openid:appauth`) +
  `SuiteAuthManager` (PKCE code flow → `/token` → app server `/auth/suite` → TokenStore).
  **Manifest landmine:** AppAuth's `RedirectUriReceiverActivity` inherits the app theme; the suite
  apps use `android:Theme.Material.*` (not AppCompat) which crashes it on redirect. Every client
  manifest overrides that activity with `android:theme="@style/Theme.AppCompat.Translucent.
  NoTitleBar"` + `tools:node="merge"` — do not remove.
- **Local tests:** `.venv\Scripts\python.exe -m pytest` with a throwaway database (CI spins a
  Postgres service; locally reuse a sibling db container and an override `DATABASE_URL` pointing
  at `127.0.0.1` — never `localhost`, IPv6-first resolution stalls every connection).

### Arming the weekly digest (owner one-time)

The digest ships **off**. It stays inert — the read/generate endpoints 404 and the Sunday scheduler
returns immediately — until the owner sets these in `server/.env` and redeploys
(`docker compose up -d --force-recreate server`; Compose won't re-read a changed `.env` on a plain
`up`):

- `DIGEST_OWNER_EMAIL` — the single SSO email the digest is built for. **Empty ⇒ whole feature off.**
- `DIGEST_READ_KEY` — the bearer the hub sends as `X-Digest-Key`. **Empty ⇒ the read/generate
  endpoints 404.** Put the same value in the hub's Settings → "Weekly digest" (read key). Generate
  with `openssl rand -hex 32`.
- Per-app base URLs the aggregator pulls: `SPOTTER_BASE_URL`, `PLATE_BASE_URL`, `COOKBOOK_BASE_URL`,
  `MAGPIE_BASE_URL`. Any unset ⇒ that domain is silently skipped (the digest degrades). **Magpie is
  tailnet-only — use its `ts.net` URL** (the host reaches it over the tailnet), not a public host.
- LM Studio narration (optional): `LM_STUDIO_BASE_URL` (default `http://host.docker.internal:1234/v1`
  — inside the container `localhost` is the container, so it points at the host) and `LM_STUDIO_MODEL`
  (default `google/gemma-4-e4b`). Unreachable ⇒ the digest still ships numbers-only.
- ntfy nudge (optional): `NTFY_BASE_URL` + `NTFY_DIGEST_TOPIC`. Empty ⇒ no push.

**Smoke-test after arming:** `POST /digest/generate` with the `X-Digest-Key` header returns the
generated digest (400 if `DIGEST_OWNER_EMAIL` is still unset). The hub's Settings must carry the
same server URL (default `https://id.dragonflymedia.org`) + read key for its "Your week" screen.

## Suite-wide release gotchas (learned on-device)

- A local debug build's versionCode (single digits) is far below any CI release (epoch minutes,
  ~30M) — `adb install` over a CI-installed app fails; uninstall first, or build a release-signed
  APK with the suite key. Never hand-install an APK with an artificially high versionCode: it
  permanently escapes the auto-update train until the app is uninstalled.
- `adb` over Wi-Fi works to the house phone; pull crashes with `adb logcat -d -b crash`. Verify
  any APK's signer with `apksigner verify --print-certs` before blaming the update flow.
