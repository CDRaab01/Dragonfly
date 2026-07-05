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

## Update sources (configurable)
Two backends, selectable per-app or globally in settings:

### 1. GitHub Releases
- Poll `GET /repos/{owner}/{repo}/releases/latest` via GitHub REST API.
- **Each sibling's release must include a `version.json` asset** (uploaded by its release CI) alongside the `.apk`:
```json
{ "versionCode": 42, "versionName": "1.4.2", "sha256": "…", "minSdk": 26 }
```
  This is required because the GitHub API only exposes the semver tag — `versionCode` (the actual source of truth) has to be published explicitly. Note this requirement in each sibling's CLAUDE.md / release workflow.
- Parse `version.json` + the `.apk` asset URL; `tag_name` is display-only.
- Optional PAT stored in DataStore for private repos / rate limits.
- If a release lacks `version.json`, surface it as "unknown version — fix the release" rather than guessing from the tag.

### 2. Self-hosted (Dragonfly server via Tailscale Serve)
- Fetch a JSON manifest over **HTTPS via Tailscale Serve** at the node's MagicDNS name, e.g. `https://dragonfly.<tailnet>.ts.net/manifest.json`. No cleartext exception needed; hostname survives IP changes.
- Manifest schema (per app):
```json
{
  "spotter":   { "versionCode": 42, "versionName": "1.4.2", "apkUrl": "…/spotter-1.4.2.apk", "sha256": "…", "minSdk": 26 },
  "plate":     { "versionCode": 30, "versionName": "1.2.0", "apkUrl": "…", "sha256": "…" },
  "cookbook":  { "versionCode": 11, "versionName": "0.9.1", "apkUrl": "…", "sha256": "…" },
  "hawksnest": { "versionCode": 88, "versionName": "2.1.0", "apkUrl": "…", "sha256": "…" }
}
```
- Reachability check first; fall back to GitHub source (or surface offline state) if the Tailscale host is unreachable.

## Version comparison
- Compare installed `versionCode` (via `PackageManager.getPackageInfo`) against source's `versionCode` — available from both backends now that GitHub releases ship `version.json`.
- `versionCode` is the source of truth; show `versionName` in UI.
- Sibling apps must ship monotonically increasing `versionCode` — note this in each sibling's build config.

## Update flow
1. Refresh: query each app's configured source for latest version (manual, on-launch, or WorkManager periodic per settings).
2. Diff against installed versions → mark "Update available" (notification if from background check).
3. On tap: download APK to app-scoped storage.
4. **Verify SHA-256** against the manifest / `version.json` value — mandatory for both sources.
5. Launch `PackageInstaller` session → system prompt → install.
6. Record result (success/version/timestamp) in update history.

## Settings (shared)
DataStore-backed, exposed to sibling apps as needed:
- Update source selection (per-app override + global default)
- GitHub PAT (encrypted — use `EncryptedSharedPreferences` or DataStore + Tink for secrets)
- Self-host base URL (MagicDNS HTTPS)
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
  builds only stay binary-compatible when they match (currently AGP 8.5.0 / Kotlin 2.0.0 /
  BOM 2024.06.00).

## Permissions (manifest)
- `INTERNET`
- `REQUEST_INSTALL_PACKAGES`
- `ACCESS_NETWORK_STATE` (Wi-Fi-only gating, reachability)
- `POST_NOTIFICATIONS` (API 33+, for background "update available" nudges)

## Screens
- **Home (launcher/dashboard):** app cards (Spotter / Plate / Cookbook / Hawksnest) — tap to launch the installed app; each card shows installed version, latest version, status, and a per-app update button when one is available. Global "Check all". Cards for not-yet-installed apps offer install. A **suite-status banner** ("All systems go" / "N down") sits below the hero and taps through to Suite status.
- **Suite status (v2 dashboard):** live "is my world green" surface — per-service up/down for the 4 suite backends (with version·commit + last-deploy from `/version`) and the media stack (reachability only), grouped Suite / Media / Automation. See the v2 build log below.
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
| dragonfly | `com.dragonfly` | CDRaab01/Dragonfly |

Every managed package must also appear in the manifest `<queries>` block (API 30+ package
visibility) or version reads and launch intents silently fail.

## Constraints / notes
- **Update `ARCHITECTURE.md` in the same PR** when a change alters architecture — a module's
  responsibility, a layer boundary, a cross-app/identity contract, or the data model. Silently-drifting
  docs are how Spotter's API docs said `/plans` for a round (ROADMAP2 T2 #5c).
- No Play Store; sideload-only suite. Do not add Play-specific APIs.
- SHA-256 verification is mandatory for both sources (self-host manifest and GitHub `version.json` both publish hashes).
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
- [ ] Set up Tailscale Serve on the Dragonfly server + record the MagicDNS manifest URL here
      (the app's Settings → Self-host base URL; empty = self-host source unavailable).
- [ ] Who generates the self-host manifest — CI step vs manual.
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
  (HTML form + session cookie), `/logout`, `POST /cross-app/token` (confidential
  client_credentials → short-lived RS256 cross-app token, `aud="cross-app"`; enabled by
  `CROSS_APP_CLIENTS`, disabled/404 when unset — ROADMAP T2 #5, see [CROSS-APP.md](CROSS-APP.md)).
- **Tokens:** RS256 via Authlib JOSE. Access tokens `aud=suite` (what app servers verify);
  id_tokens `aud=<client_id>` + nonce. Static public clients: `spotter`, `plate`, `cookbook`,
  `dragonfly`, plus `localdev` for tests. Redirect URIs are `<package>:/oauth2redirect`.
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

## Suite-wide release gotchas (learned on-device)

- A local debug build's versionCode (single digits) is far below any CI release (epoch minutes,
  ~30M) — `adb install` over a CI-installed app fails; uninstall first, or build a release-signed
  APK with the suite key. Never hand-install an APK with an artificially high versionCode: it
  permanently escapes the auto-update train until the app is uninstalled.
- `adb` over Wi-Fi works to the house phone; pull crashes with `adb logcat -d -b crash`. Verify
  any APK's signer with `apksigner verify --print-certs` before blaming the update flow.
