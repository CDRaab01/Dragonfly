# CLAUDE.md — Dragonfly (Suite Hub)

## Purpose
**Dragonfly** is the central hub app for the personal Android app suite. Three jobs:
1. **Launcher/dashboard** — home surface with cards for the sibling apps (**Spotter**, **Plate**, **Cookbook**, **Hawksnest**): launch installed apps, see installed vs. latest version at a glance.
2. **Shared settings** for the suite.
3. **Update deployment channel** — sibling APKs update in-app instead of manually pulling from GitHub.

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
- **Home (launcher/dashboard):** app cards (Spotter / Plate / Cookbook / Hawksnest) — tap to launch the installed app; each card shows installed version, latest version, status, and a per-app update button when one is available. Global "Check all". Cards for not-yet-installed apps offer install.
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
- No Play Store; sideload-only suite. Do not add Play-specific APIs.
- SHA-256 verification is mandatory for both sources (self-host manifest and GitHub `version.json` both publish hashes).
- Keep Dragonfly itself updatable via the same flow (self-update path) — it lists itself as a managed app.
- The Dragonfly server may be down; every network path must degrade gracefully.

## Open items
- [x] ~~Confirm package names~~ — done, see the registry table.
- [ ] Decide if settings are truly shared to siblings (ContentProvider) or Dragonfly-local only.
      (v1 built Dragonfly-local; nothing consumes shared settings yet.)
- [ ] Same signing key across suite? Each app currently commits its own "stable" keystore
      (Dragonfly: `dragonfly-debug.keystore`, alias `dragonfly`, password `dragonfly01` —
      Spotter/Cookbook convention). Required before signature-permission sharing.
- [ ] Set up Tailscale Serve on the Dragonfly server + record the MagicDNS manifest URL here
      (the app's Settings → Self-host base URL; empty = self-host source unavailable).
- [ ] Who generates the self-host manifest — CI step vs manual.
- [x] ~~Add the `version.json` upload step to each sibling's release workflow~~ — done
      2026-07-02 across the suite (schema: `{"versionCode", "versionName", "sha256", "minSdk"}`):
      Spotter `release.yml`, Plate + Cookbook `ci.yml` publish jobs, Hawksnest
      `android-release.yml` (which previously only uploaded a CI artifact — it now publishes a
      real GitHub Release tagged `android-v0.1.N` so `releases/latest` works). Existing releases
      predate the asset; each app needs one new release cut before its GitHub check goes green.
- [x] ~~GitHub Actions CI + release workflow~~ — `.github/workflows/ci.yml`: unit tests +
      assembleDebug on push/PR (Pulse checked out as sibling), signed release APK +
      `version.json` published on `v*` tags (Cookbook pattern; stable-key fallback when the
      KEYSTORE_* secrets are absent).
- [ ] Push to GitHub (CDRaab01/Dragonfly) — needs the human's credentials.
- [ ] Dashboard v2 candidates (out of v1 scope): live service status from the server stack
      (Plex, Cookbook server, etc.).

---

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
