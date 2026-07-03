# Dragonfly

The hub of a personal, self-hosted Android app suite (**Spotter** · **Plate** · **Cookbook** ·
**Hawksnest**). One repo, two deliverables:

- **`android/`** — the Dragonfly app (Kotlin/Compose): launcher/dashboard for the sibling apps,
  in-app APK update channel (GitHub Releases or a self-hosted manifest, SHA-256 verified,
  `PackageInstaller` flow), shared suite settings, and the suite's **config broker** — a
  signature-permission ContentProvider siblings read their server URLs from.
- **`server/`** — **dragonfly-id**, the suite's OIDC identity server (FastAPI, RS256/JWKS,
  authorization-code + PKCE). Powers "Sign in with Dragonfly" single sign-on in the sibling apps.

The suite is sideload-only (no Play Store): every app auto-publishes a signed release APK +
`version.json` on push to `main`, and phones update through Dragonfly. All five apps share one
signing key, which is what makes the broker's signature permission and in-place updates work.

## Docs

- [CLAUDE.md](CLAUDE.md) — architecture, conventions, as-built status (start here)
- [BROKER.md](BROKER.md) — the config/identity broker design and phase plan
- [server/DEPLOY.md](server/DEPLOY.md) — identity-server deployment checklist
- [deploy/README.md](deploy/README.md) — push-to-deploy for the server

## Develop

Android: open `android/` (requires the sibling [Pulse](https://github.com/CDRaab01/Pulse) repo
checked out next to this one — composite build). Server: `server/.venv`, Python 3.12,
`python -m pytest` + `ruff check .`; run the stack with `docker compose up -d --build`.
