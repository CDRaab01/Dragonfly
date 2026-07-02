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

## Phase 2 — Suite SSO via a Dragonfly identity server

> **Gate resolved (user, 2026-07-02): SHAPE 1 — build a Dragonfly identity server.** Chosen over
> the lighter UX-only option because the user wants to move toward a real single-identity
> architecture (headroom for multi-user / central revocation / web SSO later), accepting the new
> always-on service and the deliberate exception to "no shared backend components". Shapes 2 & 3
> are recorded in git history if we ever need to reconsider.

**Structural premise:** SSO needs one authentication authority; today there are five co-equal ones
(each app owns its `users` table + JWTs, federated only by email via server-side
`CROSS_APP_SECRET`). Phase 2 introduces that authority as a new service and migrates the apps to
trust it — *additively and behind flags*, so every app keeps working throughout.

### New component: `dragonfly-id` (identity server)
A small FastAPI service following suite conventions (Docker Compose on the DragonflyMedia host,
SQLAlchemy 2.0 async + Alembic, `/health`+`/version`, self-hosted-runner redeploy, Cloudflare
subdomain). **Lives in the Dragonfly repo under `server/`** (the hub owns identity; keeps them
together — Dragonfly is currently Android-only, so this adds the first server code here).

- **Identity model:** `users(id, email UNIQUE, name, password_hash [argon2id], created_at, reset
  fields)`. One row per person; for now that's just you.
- **Tokens:** asymmetric **RS256** access + refresh JWTs. Public keys served at
  `/.well-known/jwks.json` so app servers validate signatures with the *public* key and never
  share a secret. Claims: `iss` (the id server), `aud` (`suite`), `sub` (user id), `email`,
  `exp`. Key id (`kid`) in the header; rotation supported by publishing multiple JWKS keys.
- **Endpoints:** `register`, `login`, `refresh`, `logout`/revoke, `forgot`/`reset`, `GET /me`,
  JWKS. Rate limits + security headers mirroring Spotter's auth. Secrets/keys via env, never
  committed.

### Trust integration in each app server (2b — additive, flagged)
- Config `SUITE_JWKS_URL` (+ expected `iss`/`aud`). **Unset ⇒ the app behaves exactly as today**
  (per-app login only). Set ⇒ the app also accepts suite tokens.
- New endpoint `POST /auth/suite`: accept a suite **access** token → verify via cached JWKS →
  extract email → find-or-create the local user by email (the existing `get_cross_app_user`
  email-federation pattern is the reference) → return **that app's own** session tokens. So the
  app's entire downstream session model is unchanged; the suite token only appears at login.
- Dual-auth during migration: per-app password login keeps working the whole time. Nothing is a
  hard cutover.

### Android delegation (2c)
- **Dragonfly owns the suite session** (holds the suite refresh token in its encrypted store, the
  same one the GitHub PAT uses). Login UI lives in Dragonfly.
- A sibling with no local session asks Dragonfly for a short-lived suite **access** token, then
  calls its own `POST /auth/suite` to exchange it for a local session, stored in its existing
  token store — its `TokenRefreshAuthenticator` machinery is untouched.
- **DECISION (recommended): native delegation over a signature-gated bound `Service`** in
  Dragonfly that mints/returns a fresh suite access token — *not* a web/Custom-Tab OAuth flow.
  The shared suite signing key already establishes trust between the apps, so a browser round-trip
  and PKCE add ceremony without benefit here; native is simpler and works offline against the
  tailnet. (A provider is the wrong tool for minting tokens — use a `Service`/`Messenger`.)
- **Fallback (mandatory, Phase-1 ethos):** Dragonfly absent / not same-signed / no suite session
  ⇒ the sibling shows its own login screen and authenticates directly, exactly as today.

### Migration (one-time, single user)
Register once in Dragonfly (your email + a password) — that becomes the authority. Existing
per-app accounts **auto-link by email** on first suite login (no password export needed; the old
per-app hashes stay valid for dual-auth). Because everything is additive + flagged, you migrate
app-by-app at your pace with zero downtime.

### Sub-phases (each exits with tests green + one app end-to-end before the next)
- **2a — identity server.** Scaffold `server/` in the Dragonfly repo: model + Alembic, auth
  endpoints, RS256/JWKS, rate limits, Docker Compose, `/health`+`/version`, CI + runner deploy.
  Dragonfly Android gets the login UI + suite session store. Exit: register/login/refresh work
  against the deployed service; JWKS validates.
- **2b — one app server trusts suite tokens.** Pilot with **Cookbook's** server (youngest, cleanest):
  `SUITE_JWKS_URL` + `POST /auth/suite` + email find-or-create, fully behind the flag. Exit:
  a suite token logs into Cookbook's API; flag off = unchanged.
- **2c — one sibling client delegates login.** Cookbook Android: no session ⇒ pull a suite token
  from Dragonfly ⇒ `/auth/suite` ⇒ store local session; fallback to its own login otherwise.
  Exit: fresh Cookbook install signs in with no Cookbook-specific login, via Dragonfly.
- **2d — roll out** to Spotter + Plate (server + client each), then decide Hawksnest.
- **2e (optional, later)** — retire per-app password endpoints once everything runs on suite auth
  (keep a break-glass path).

### Open decisions to confirm before 2a
1. **Delegation mechanism** — native signature-gated `Service` (recommended) vs. web OAuth
   (Custom Tabs + PKCE). Shapes all of 2c and the client work.
2. **Deployment** — new `id.dragonflymedia.org` (needs a Caddy route + Cloudflare hostname + a
   `dragonfly` self-hosted runner). Confirm the subdomain and that the DragonflyMedia host will
   run one more container.
3. **Repo** — identity server in the Dragonfly repo `server/` (recommended) vs. its own repo.

---

## Sequencing & effort

| Phase | Repos touched | Human-required | Risk |
|---|---|---|---|
| 0 — key unification | all 5 (workflow pins) + GitHub secrets | secrets in 5 repos; phone reinstall ×5; keystore backup | one-way door |
| 1 — config broker | Dragonfly (provider+UI) + 4 siblings (small read patch) | none beyond normal updates | low; fallback keeps siblings independent |
| 2 — SSO (shape 1) | new `dragonfly-id` service + all app servers + clients | confirm 3 open decisions; new subdomain + runner; register once | high; additive + flagged, staged app-by-app |

Phase 1 is worth shipping even if Phase 2 never happens. Phase 2's shape is decided (identity
server); confirm the three open decisions above before starting sub-phase 2a.
