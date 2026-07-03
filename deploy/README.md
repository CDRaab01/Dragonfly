# Deploy — dragonfly-id (self-hosted runner)

Push-to-deploy for the identity server, mirroring the other suite apps. The self-hosted runner
long-polls GitHub outbound (no inbound ports). Auto-deploy fires after **Server CI** goes green on
`main`; `workflow_dispatch` is a manual button / rollback lever.

## One-time host setup

1. **Runner** registered on the DragonflyMedia host with the label **`dragonfly`** (done).
2. **`DRAGONFLY_DIR` Actions variable** — set it to the canonical clone path on the host, e.g.
   `C:\Code\Dragonfly` (repo → Settings → Secrets and variables → Actions → Variables → New).
   The redeploy operates on *this* clone (it owns `server/.env`, the OIDC key, and the pgdata
   volume), not the runner's ephemeral checkout.
3. That clone must have `server/.env` (with a **stable** `OIDC_PRIVATE_KEY`) and the root `.env`
   (`TUNNEL_TOKEN`, `COMPOSE_PROFILES=tunnel`) already in place — they're gitignored and survive
   `git reset --hard`.

## What a deploy does

`deploy/redeploy.ps1`: `git fetch` → `git reset --hard <ref>` → `docker compose up -d --build`
(migrations run on container boot) → health-gate on `http://127.0.0.1:8004/health` → prune. It
stamps `GIT_SHA`/`BUILT_AT` so `GET /version` reports the running commit.

## Manual deploy / rollback

Actions → **Deploy** → Run workflow. Leave `ref` as `origin/main`, or pass a prior commit SHA to
roll back. Or on the host directly: `powershell deploy/redeploy.ps1 -Ref <sha>`.
