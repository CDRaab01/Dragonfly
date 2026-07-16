# ROADMAP.md — Dragonfly (departing-engineer assessment, 2026-07-03)

Covers both deliverables: the hub app (`android/`) and dragonfly-id (`server/`). Dragonfly is
suite infrastructure — its roadmap is mostly about making the suite self-observing and finishing
what BROKER.md started.

## Road to 1.0 (suite pivot, 2026-07-13) — the hub becomes the suite's face

The suite entered its **1.0 polish round** (host-level ROADMAP3, C:\Code): every app must pass a
shared bar (onboarding, designed states, motion + dark/light parity, defined offline behavior,
no dead settings, on-device pass, gating baselines, icon, truthful docs), `versionName` 1.0.0 as
the round's last commit. The hub's round (host Tier W3) rolls up items already in this file:

1. ✓ **On-device pass** — DONE 2026-07-15. Verified on a real phone (v0.1.45): Home dashboard
   renders, Suite-status screen renders + tap-through (backends UP with version·commit·deploy-time),
   correct installed→latest readouts, and — the never-before-run path — the full **update/install
   flow** (GitHub release → download → SHA-256 verify → PackageInstaller system prompt → install)
   worked end-to-end updating Spotter. Notifications not yet exercised on-device.
2. ✓ **Delete the dead self-host update source** — DONE 2026-07-15. Removed `UpdateSource`,
   the manifest-fetch path (`ManifestEntry`/`parseManifest`/`fetchManifestIfNeeded`), the
   per-app + global source selection (Settings + App-detail toggles), `selfHostBaseUrl`, and the
   broker's `selfhost_base_url` row — the hub updates from GitHub Releases, full stop. The GitHub
   fetch/download/verify/install path is untouched. Cross-app safe: no sibling read
   `selfhost_base_url` (`SuiteConfigReader` reads only `server_base_url`).
3. **Update flow polish** (fun tier #2): ✓ **"changed since installed" release-notes rollup DONE
   2026-07-15** — App detail now rolls up every release's notes since your installed build (matched
   by versionName→tag; `ReleaseResolver.notesSinceInstalled`), not just the latest, headed "Changes
   since <version>". Still open: background download-then-notify (tap the notification → straight to
   the install prompt).
4. ✓ **dragonfly-id self-service** (hardening #2) — DONE 2026-07-15. Session-cookie-gated
   `/account` page: password change + active-session list/revoke (individually or "sign out of
   all apps"). Revoke leans on `/token` already rejecting `revoked` tokens; sessions are keyed by
   a new surrogate `id` on the refresh token (migration `0002`), never the secret value.
5. **Magpie tile upgrade**: net-this-month via `/cross-app/summary` (Magpie ROADMAP #22).
6. This repo also hosts the two suite-wide wow tracks: the **shared push pipeline** (fun tier
   #3, ntfy — Hawksnest's V1 already deployed the backend) and the **suite weekly digest**
   surface (host Tier W1). ✓ **Weekly digest DONE 2026-07-16** — a digest service inside
   dragonfly-id (`server/app/digest/`, migration 0003): a Sunday-evening scheduler → an aggregator
   that mints an in-process cross-app token for the owner and pulls Spotter/Plate/Cookbook/Magpie
   range endpoints best-effort → LM Studio narrates (degrades to numbers-only) → one `weekly_digests`
   row/week → an ntfy nudge; `GET /digest/weekly` (header `X-Digest-Key`) serves it. The hub has a
   "Your week" digest screen (narrated paragraph + four Training/Nutrition/Cooking/Money cards, each
   optional) + a Settings "Weekly digest" section. **Dormant until the owner arms the server `.env`**
   (`DIGEST_OWNER_EMAIL` + `DIGEST_READ_KEY` + the per-app base URLs, etc. — see CLAUDE.md "Arming
   the weekly digest"); endpoints 404 and the scheduler no-ops until then.
7. Version 0.1.0 → **1.0.0** at the gate — deliberately last among the six apps.

**Gap review 2026-07-14 (host ROADMAP3 additions):** the suite bar gained accessibility (#11)
and biometric-where-warranted (#12); two suite tracks land in this repo's court — **retention
nudges** (Tier W2b: the product layer on the shared ntfy pipeline — per-app opt-in reminder
settings, quiet hours) and the **Pulse widget family** (Tier W4: hub suite-status widget +
shared Glance theming primitives in Pulse). ✓ **Hub suite-status widget DONE 2026-07-15** — a
Glance home-screen "suite at a glance" grid (`widget/DragonflyWidget`): each suite app + a status
dot, reading the persisted `StatusSnapshotStore` last-known snapshot (never probes network);
`StatusRepository.refresh()` writes the snapshot + pokes `WidgetRefresher` after every probe pass.
Still open under W4: promoting the shared Glance theming primitives up into Pulse. ✓ **Static app shortcuts DONE 2026-07-15** —
long-press the hub icon → "Check updates" (opens Home, which refreshes all apps) / "Suite status"
(jumps to the status screen). `res/xml/shortcuts.xml` + a `dragonfly://shortcut/<target>` VIEW
intent routed in `MainActivity`/`DragonflyNavGraph` (handles the singleTask warm re-launch too).

## Finish what's started

1. ~~Set `DRAGONFLY_DIR`~~ — done 2026-07-03; push-to-deploy verified live.
2. **Self-host update source: build it or delete it.** The Settings field has been "unset by
   default" since v0.1 with no manifest behind it. Building = a small CI step publishing
   `manifest.json` + APKs behind Tailscale Serve. If GitHub Releases has proven sufficient
   (it has so far), delete the setting — a permanently-dead option is UX debt and untested code.
3. **BROKER.md 2e — retire per-app passwords** once SSO has soaked for a few weeks: flag off
   `/auth/register` + password `/auth/login` per app, leaving `/auth/suite` + reset flows.
   Do it one app at a time (Cookbook first, as always). Prereq: #1 below under identity server.

## dragonfly-id hardening (it's the suite's root of trust now)

1. **Key rotation with `kid`.** Verify the JWKS/token path handles two published keys (sign
   with new, keep old until expiry), then write the rotation runbook. Currently
   `OIDC_PRIVATE_KEY` is a single static key in `.env` — rotation today would be a
   flag-day outage across three apps.
2. ✓ **Self-service account surface** — DONE 2026-07-15. `GET /account` (session-cookie-gated
   HTML): password change + active-session list/revoke + revoke-all. Reachable via "Manage your
   account" on the login page. A hub-app card (deep-link to `/account`) is a possible follow-up.
3. **Refresh-token hygiene:** ✓ **prune DONE 2026-07-15** — `app/maintenance.prune_stale_tokens`
   sweeps revoked/expired refresh tokens + expired auth codes on startup (a FastAPI lifespan hook,
   defensive/non-fatal), so the table self-cleans each deploy without a separate scheduler. Still
   open: alerting on anomalous issuance (uptime-kuma can watch a `/health`-style stats endpoint).
4. **Backups:** the identity DB is now the most important 20 MB on the host — it's in the
   suite-wide backup item (host ROADMAP Tier 1 #1) but worth naming here: losing it strands
   every app's SSO link.

## Hub app (the fun tier)

1. ✓ **v2 dashboard — live suite status — SHIPPED 2026-07-04** (`status/` package:
   `StatusResolver` + parallel `StatusProber`, Home banner → Suite status screen, grouped
   Suite/Media/Automation; Magpie onboarded 2026-07-08). On-device render/tap-through still
   owed (Road to 1.0 #1); Hawksnest's URL is still the guessed `:30080`.
2. **Update flow polish:** background download-then-notify (tap = straight to the install
   prompt), and per-app release notes already render — add "changed since installed" when
   multiple releases behind.
3. **Shared push channel.** If/when the suite gets push (host ROADMAP Tier 3), the hub is the
   natural owner: one token, one relay, siblings publish via the broker seam. Hawksnest's
   doorbell case is the forcing function.

## Explicitly not worth it

- Silent APK installs (device-owner/root) — re-litigated periodically; the one-tap system
  prompt is the right cost for a personal suite.
- Moving the identity server to its own repo — the repo boundary (`android/` + `server/`)
  has caused zero friction and shares the release/deploy plumbing.
