# Deploying dragonfly-id

The suite identity provider (BROKER.md Phase 2). Same operational model as the other suite
servers: Docker Compose on the DragonflyMedia host, migrations on boot, a Cloudflare tunnel for
public access, and a self-hosted GitHub Actions runner for push-to-deploy.

## Local / first run

```bash
cd server
cp .env.example .env          # set SECRET_KEY; ISSUER=http://localhost:8004 for local
cd ..
docker compose up -d --build  # db on 127.0.0.1:5435, API on 127.0.0.1:8004
curl http://127.0.0.1:8004/health
```

Create your account once, then close registration:

```bash
curl -X POST http://127.0.0.1:8004/register \
  -H 'content-type: application/json' \
  -d '{"name":"You","email":"you@example.com","password":"<pick one>"}'
# then set REGISTRATION_INVITE_CODE in .env (or remove /register access) and redeploy
```

## Production checklist (human infra — one-time)

1. **Stable OIDC key.** Generate once and put it in `server/.env` as `OIDC_PRIVATE_KEY` (a restart
   with an auto-generated key invalidates every live token):
   `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`
2. **`ISSUER=https://id.dragonflymedia.org`** in `.env` — must exactly match what the app servers
   validate as the token `iss`.
3. **Cloudflare tunnel:** add a public hostname `id.dragonflymedia.org` pointing at
   `http://server:8000`, put its token in the root `.env` as `TUNNEL_TOKEN`, and set
   `COMPOSE_PROFILES=tunnel` so `docker compose up` keeps cloudflared in the managed set.
   (Caddy is not required — the tunnel reaches the container directly, like the other apps.)
4. **`TRUST_PROXY=true`, `HSTS_ENABLED=true`, `DOCS_ENABLED=false`** in `.env` for the public deploy.
5. **Self-hosted runner** registered to `CDRaab01/Dragonfly` if you want push-to-deploy (mirror
   Spotter's `deploy/` — a `workflow_run` redeploy after Server CI passes on `main`). Until then,
   deploy by hand: `git pull && docker compose up -d --build`.

## Ports on this host

Spotter 8000/5432, Plate 8001/5433, posterizarr 8002, Cookbook 8003/5434 — dragonfly-id uses
**8004** (API) and **5435** (db).

## What the app servers need (Phase 2b, next)

Each app server will validate suite tokens against `https://id.dragonflymedia.org/.well-known/jwks.json`
(config `SUITE_JWKS_URL`), checking `iss` = ISSUER and `aud` = `suite`. Nothing to do here for that;
it's additive work in each app's own repo.
