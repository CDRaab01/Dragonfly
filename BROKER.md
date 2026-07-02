# BROKER.md — Dragonfly as the suite's config & identity broker

> Plan of record for extending Dragonfly from "update channel + launcher" into the suite's
> **connection-config broker** (one place that tells every app how to reach its server) and,
> in a later phase, its **identity broker** (one sign-in across the suite). Written 2026-07-02;
> build phases in order, do not start a phase before the prior one's exit criteria are met.

## Decisions locked (user-confirmed 2026-07-02)

- **Sharing model: signature-permission ContentProvider** (OS-enforced; only apps signed with
  the same key can read). Chosen over the shared-secret model despite requiring key unification.
- **Scope: connection config AND shared login (SSO).** Config broker ships first (Phase 1);
  SSO is Phase 2 and has its own decision gate (§Phase 2) before any code.

## Non-goals

- Dragonfly is **not a traffic proxy/VPN**. Apps keep making their own HTTPS connections;
  Dragonfly only brokers *what to connect to* (and later, *who you are*). Private-network
  access stays the Tailscale app's job.
- No shared monolith backend. The one-app-one-backend ecosystem rule stands; anything Phase 2
  adds must be a deliberate, minimal exception (see the SSO decision gate).
- Cross-app **server-to-server** auth (`CROSS_APP_SECRET`) is out of scope — it works, it's
  server-side only, and the phone apps never hold it. Don't move it into the broker.

## Existing seams this builds on (verified in code, 2026-07-02)

- **Runtime server URL:** Spotter (and its clones Plate/Cookbook) already resolve the server
  URL at runtime — `AppPreferences.serverUrl` (DataStore) applied per-request by
  `HostSelectionInterceptor` (`data/remote/HostSelectionInterceptor.kt`). The broker feeds this
  exact seam; no networking rearchitecture in any app.
- **Dragonfly shared settings:** DataStore-backed settings + Settings UI already exist in the
  hub; the broker extends them with per-app connection entries and exposes them via a provider.
- **Release discipline:** every app auto-releases on main pushes with a pinned
  signing-identity guard (see CLAUDE.md "Release automation"). Key rotation is *designed* to
  trip that guard — Phase 0 updates the pins in the same change.

---

## Phase 0 — Unify the suite signing key (prerequisite, one-way door)

Signature-permission sharing means Android grants access only to apps signed with the
**same key** as Dragonfly. Today all five apps sign with **five different keys** (fingerprints
pinned in each release workflow). This phase moves the whole suite onto one key.

**Hard requirements:**
- The suite key **must be secret** (CI secrets only, never committed). The repos are public; a
  committed key would let anyone sign an app that passes the signature check, nullifying the
  security model. Note this specifically changes Dragonfly, which currently signs releases
  with its committed stable key.
- **Back up the keystore** outside CI (password manager / offline). Losing it permanently
  locks every device out of updates (the exact class of problem the signing guard exists for).

**Steps:**
1. Generate one new keystore locally (`suite-release.jks`, RSA 2048, validity ≥ 25y, alias
   `suite`). Human stores the backup + password somewhere durable.
2. **[HUMAN]** In each of the five repos' GitHub settings, set/replace the secrets:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
   (Hawksnest uses the `HAWKSNEST_`-prefixed names). This is admin-UI work; no CLI on this
   machine can do it.
3. Compute the new key's signer SHA-256 and update `EXPECTED` in all five release workflows
   **in the same commit wave** — otherwise the guard (correctly) blocks every release.
4. Push → each repo auto-releases signed with the suite key. Verify: guard green ×5,
   `apksigner` on a downloaded APK shows the new fingerprint everywhere.
5. **[HUMAN, phone]** One-time coordinated migration: uninstall all five apps, reinstall from
   the new releases (Dragonfly first, then install the rest through it — good end-to-end
   test). All app data is server-backed; only unsynced offline data is lost. Do it when
   nothing is mid-flight (no half-finished workout/shopping trip).
6. Update the CLAUDE.md fingerprint table + memory notes to the single suite fingerprint.

**Exit criteria:** all five `releases/latest` APKs verify with one identical signer cert;
all five apps installed on the phone from those releases; updates flow normally afterwards.

**Rollback reality:** there is no clean rollback after step 5 — that's why it's a one-way
door. Before step 5, backing out = restoring the old per-app secrets + reverting the
`EXPECTED` pins.

---

## Phase 1 — Connection-config broker (client-only, no server changes)

> **Status (2026-07-02): IMPLEMENTED.** Dragonfly ships `SuiteConfigProvider` (signature-permission,
> authority `com.dragonfly.suiteconfig`) + a "Managed app servers" settings UI. Spotter, Plate, and
> Cookbook read `server_base_url` on startup via a `SuiteConfigReader` feeding their existing
> `AppPreferences.serverUrl` seam, with the mandatory fallback (absent hub / permission denied /
> blank ⇒ keep local). All four build green and auto-released. **Hawksnest deferred** — it targets a
> Home Assistant instance, not a suite FastAPI backend, so its URL semantics need their own design.
> **On-device VERIFIED 2026-07-02:** set a server URL for Cookbook in Dragonfly, relaunched
> Cookbook, and it adopted the value (the read runs in `App.onCreate`, so a process restart is
> required to pick up a change).

**What it is:** Dragonfly exposes a read-only `ContentProvider` with each app's connection
config; each sibling reads it on launch and feeds its existing runtime-URL seam. One place to
repoint the suite (e.g. Cloudflare hostname → Tailscale MagicDNS) — flip it in Dragonfly,
every app follows.

**Provider contract (the suite-wide interface — keep stable):**
- Authority: `com.dragonfly.suiteconfig`
- Permission: `com.dragonfly.permission.READ_SUITE_CONFIG`, declared by Dragonfly with
  `android:protectionLevel="signature"`; each sibling adds `<uses-permission>`.
- Query: `content://com.dragonfly.suiteconfig/config/{appKey}` → one row,
  columns `key TEXT, value TEXT` per entry:
  - `server_base_url` — that app's backend base URL (empty = broker has no opinion)
  - `selfhost_base_url` — the shared Tailscale/self-host base (for apps that care)
  - `updated_at` — epoch ms, lets clients skip re-applying unchanged config
- Also `config` (no key) → all apps' rows, for diagnostics.
- Versioning: additive columns/keys only. Renames/removals require bumping the path to
  `/v2/…` and keeping `/config` serving until every sibling migrates.

**Dragonfly side:**
- Provider implementation reading the same DataStore the Settings UI writes; exported with the
  signature permission; **read-only** (no `insert/update/delete` — config is edited only in
  Dragonfly's UI).
- Settings UI grows a per-app "Server URL" field (registry-driven, same screen pattern as the
  existing self-host URL).
- Reachability stays in Dragonfly's UI (it already checks hosts); do NOT push reachability
  through the provider in v1 — it's volatile state, not config.

**Sibling side (Spotter, Plate, Cookbook, Hawksnest — one small PR each):**
- On app start (and on `ON_RESUME` of the settings screen), query the provider; if a
  non-empty `server_base_url` is returned and differs from local, write it into the app's
  existing `AppPreferences.serverUrl`. The `HostSelectionInterceptor` does the rest.
- **Fallback is mandatory:** provider missing (Dragonfly not installed), permission denied
  (signature mismatch), or empty value ⇒ keep the app's locally-configured URL, exactly as
  today. The broker is an override, never a dependency.
- Precedence: broker value > local setting > build-time default. Show the effective source in
  the app's settings ("Managed by Dragonfly") so it's never mysterious.

**Tests / exit criteria:**
- Unit: provider query contract (cursor shape, unknown appKey, permission enforcement via
  Robolectric where feasible); sibling-side merge logic (broker > local > default, fallback
  paths).
- On-device: set a bogus URL in Dragonfly → sibling fails accordingly; correct it → sibling
  recovers without reinstall; uninstall Dragonfly → siblings keep working on local config.
- Exit: repoint one real app (e.g. Cookbook) between its Cloudflare URL and a second URL live
  from Dragonfly's settings, with zero sibling-side edits.

---

## Phase 2 — Shared login / SSO (GATED: pick the shape first)

**The structural problem:** SSO needs one authentication authority; today there are five
co-equal ones (each app's server owns its own `users` table + JWTs, federated only by email
via server-side `CROSS_APP_SECRET`). A client-side broker cannot fix this alone — the phone
can't hold a secret that mints server-trusted tokens (APKs are decompilable).

**The three viable shapes (pick ONE before any Phase 2 code):**

1. **Dragonfly identity server (recommended if SSO is truly wanted).** A small FastAPI auth
   service (this repo, `server/`, suite conventions: Docker Compose on the DragonflyMedia
   host, `/health`+`/version`, runner deploys). OIDC-lite: password login → RS256 JWTs;
   public JWKS endpoint; each app server adds "also accept suite-issued tokens" (verify via
   JWKS, map identity by email) alongside its existing auth during migration. Apps' clients
   delegate login to Dragonfly (app-to-app intent, or Custom Tab). True SSO; the cost is a
   new always-on service and a deliberate exception to "no shared components".
2. **Elect an existing server (e.g. Spotter's) as identity provider.** No new service, same
   token work — but couples every app's availability to Spotter's server and muddies each
   repo's single-purpose design. Cheaper, uglier long-term.
3. **UX-only credential convenience (not real SSO).** Dragonfly stores per-app refresh
   tokens after you log in once per app; siblings pull their token from the broker instead of
   showing a login screen. No central authority, no server changes, fully decoupled — but the
   accounts stay five separate accounts, and password changes still happen five times.

**Recommendation:** shape 1 — it's the only real SSO, and the identity service is small and
suite-owned. But it consciously reverses the "no shared backend components" rule, so it needs
an explicit yes at this gate. If that reversal feels wrong when the moment comes, shape 3
delivers most of the daily convenience with zero architectural cost.

**Sketch for shape 1 (only after the gate):**
- 2a: identity server (register/login/refresh, JWKS, argon2, rate limits mirroring Spotter's)
  + account linking by email; Dragonfly app gets the login UI.
- 2b: each app server accepts suite JWTs (JWKS validation, email→local-user mapping,
  feature-flagged; existing logins keep working).
- 2c: sibling clients delegate to Dragonfly for login; store returned tokens in their existing
  auth stores (their `TokenRefreshAuthenticator` machinery is untouched).
- 2d (optional, later): disable per-app password endpoints once everything runs on suite auth.
- Each sub-phase exits with tests green + one app end-to-end before the next starts.

---

## Sequencing & effort

| Phase | Repos touched | Human-required | Risk |
|---|---|---|---|
| 0 — key unification | all 5 (workflow pins) + GitHub secrets | secrets in 5 repos; phone reinstall ×5; keystore backup | one-way door |
| 1 — config broker | Dragonfly (provider+UI) + 4 siblings (small read patch) | none beyond normal updates | low; fallback keeps siblings independent |
| 2 — SSO | gate first; shape 1 = all 5 servers + clients + new service | shape decision; deploy secrets | high; staged behind flags |

Phase 1 is worth shipping even if Phase 2 never happens. Phase 2 must not start until the
shape is chosen at the gate above.
