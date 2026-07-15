# ROADMAP.md — Dragonfly (departing-engineer assessment, 2026-07-03)

Covers both deliverables: the hub app (`android/`) and dragonfly-id (`server/`). Dragonfly is
suite infrastructure — its roadmap is mostly about making the suite self-observing and finishing
what BROKER.md started.

## Road to 1.0 (suite pivot, 2026-07-13) — the hub becomes the suite's face

The suite entered its **1.0 polish round** (host-level ROADMAP3, C:\Code): every app must pass a
shared bar (onboarding, designed states, motion + dark/light parity, defined offline behavior,
no dead settings, on-device pass, gating baselines, icon, truthful docs), `versionName` 1.0.0 as
the round's last commit. The hub's round (host Tier W3) rolls up items already in this file:

1. **On-device pass first** — the hub has never run on the phone (see CLAUDE.md); nothing else
   in this list is trustworthy until PackageInstaller/notifications/status render are verified.
2. **Delete the dead self-host update source** ("Finish what's started" #2 — decision: GitHub
   Releases has proven sufficient; delete beats build).
3. **Update flow polish** (fun tier #2): background download-then-notify, "changed since
   installed" release-notes rollup.
4. ✓ **dragonfly-id self-service** (hardening #2) — DONE 2026-07-15. Session-cookie-gated
   `/account` page: password change + active-session list/revoke (individually or "sign out of
   all apps"). Revoke leans on `/token` already rejecting `revoked` tokens; sessions are keyed by
   a new surrogate `id` on the refresh token (migration `0002`), never the secret value.
5. **Magpie tile upgrade**: net-this-month via `/cross-app/summary` (Magpie ROADMAP #22).
6. This repo also hosts the two suite-wide wow tracks: the **shared push pipeline** (fun tier
   #3, ntfy — Hawksnest's V1 already deployed the backend) and the **suite weekly digest**
   surface (host Tier W1).
7. Version 0.1.0 → **1.0.0** at the gate — deliberately last among the six apps.

**Gap review 2026-07-14 (host ROADMAP3 additions):** the suite bar gained accessibility (#11)
and biometric-where-warranted (#12); two suite tracks land in this repo's court — **retention
nudges** (Tier W2b: the product layer on the shared ntfy pipeline — per-app opt-in reminder
settings, quiet hours) and the **Pulse widget family** (Tier W4: hub suite-status widget +
shared Glance theming primitives in Pulse). Cheap hub-side add: **static app shortcuts**
(long-press icon → "Check all updates" / "Suite status").

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
