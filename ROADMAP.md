# ROADMAP.md — Dragonfly (departing-engineer assessment, 2026-07-03)

Covers both deliverables: the hub app (`android/`) and dragonfly-id (`server/`). Dragonfly is
suite infrastructure — its roadmap is mostly about making the suite self-observing and finishing
what BROKER.md started.

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
2. **Self-service account surface.** The provider has login/register/logout and nothing else.
   Password change + active-session/refresh-token list + revoke — small HTML pages like
   `/login`, or a card in the hub app. Matters the day a phone is lost.
3. **Refresh-token hygiene:** a scheduled prune of expired/rotated rows, and alerting on
   anomalous issuance (uptime-kuma can watch a `/health`-style stats endpoint).
4. **Backups:** the identity DB is now the most important 20 MB on the host — it's in the
   suite-wide backup item (host ROADMAP Tier 1 #1) but worth naming here: losing it strands
   every app's SSO link.

## Hub app (the fun tier)

1. **v2 dashboard — live suite status.** Already sketched in CLAUDE.md as v2: the registry
   knows every app; add each backend's `/health` + `/version` + latest-release row and the hub
   becomes the "is my world green" surface. Server checks belong in a tiny poller (or reuse
   uptime-kuma's API) rather than the phone hammering five backends.
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
