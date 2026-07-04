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

## Rotating the OIDC signing key

Rotate on a schedule (e.g. yearly) or immediately on any suspicion the private key leaked. This is
entirely a dragonfly-id-side operation — **no app-server change and no client change** — because
apps verify by fetching the JWKS, not a pinned key.

**Why it's zero-downtime.** dragonfly-id signs with exactly one key (`OIDC_PRIVATE_KEY` /
`OIDC_KEY_ID`) but can *publish* a second, verify-only public key
(`OIDC_SECONDARY_PUBLIC_KEY` / `OIDC_SECONDARY_KEY_ID`) in `/.well-known/jwks.json`. Every token
carries a `kid` header; verifiers pick the matching published key. Two facts make the cutover
seamless (all three app servers — Spotter/Plate/Cookbook — share this exact logic in
`app/services/suite_auth.py`):

- App servers cache the JWKS for **1 hour but force one refetch on an unknown `kid`** — so a token
  signed by the *new* key is validated immediately after cutover, without waiting out the cache.
- Access tokens live **15 minutes** (`access_token_expire_minutes`); refresh tokens are opaque
  random strings, not JWTs, so they're unaffected by key changes. The only thing that must stay
  valid across the cutover is the ≤15-minute tail of access tokens signed by the *old* key — which
  is exactly what keeping the old key published in JWKS provides.

`ISSUER` does **not** change during a key rotation; leave it alone (changing it is a separate,
breaking operation).

### Procedure (recommended: sign-new, keep-old-published)

Two deploys, ~1 hour apart. Names below: `old` = the current live key, `new` = its replacement.

1. **Generate the new key** (on the host, don't commit it — keys live in `server/.env` only):
   ```bash
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out new-oidc.pem
   # public half of the OLD key, to keep published for the overlap:
   #   (extract from your current OIDC_PRIVATE_KEY, or:)
   openssl pkey -in old-oidc.pem -pubout -out old-oidc.pub.pem
   # env values are single-line with \n escapes — collapse each PEM:
   sed ':a;N;$!ba;s/\n/\\n/g' new-oidc.pem      # → OIDC_PRIVATE_KEY
   sed ':a;N;$!ba;s/\n/\\n/g' old-oidc.pub.pem  # → OIDC_SECONDARY_PUBLIC_KEY
   ```
2. **Cut over — one edit to `server/.env`:**
   - `OIDC_PRIVATE_KEY=` → the **new** private key; `OIDC_KEY_ID=` → a **new** kid (e.g.
     `dragonfly-id-2`).
   - `OIDC_SECONDARY_PUBLIC_KEY=` → the **old** public key; `OIDC_SECONDARY_KEY_ID=` → the **old**
     kid (e.g. `dragonfly-id-1`).
   Then redeploy so the container reloads `.env` (a plain `up` without recreate won't re-read it):
   ```bash
   docker compose up -d --force-recreate server
   ```
   Now: signing with `new`; JWKS serves both `new` and `old`. New-key tokens validate (unknown-kid
   refetch); the last old-key tokens keep validating (old still published).
3. **Verify** (see below), then **wait out the overlap** — ≥ 15 min (access-token lifetime); 30–60
   min is a comfortable margin that also covers clock skew.
4. **Retire the old key — second edit to `server/.env`:** clear both secondary vars
   (`OIDC_SECONDARY_PUBLIC_KEY=` / `OIDC_SECONDARY_KEY_ID=` empty) and redeploy the same way. JWKS
   now serves only `new`. Rotation complete. Securely delete `old-oidc.pem`.

### Verify

```bash
# JWKS serves both kids during the overlap (one after retirement):
curl -s https://id.dragonflymedia.org/.well-known/jwks.json | python -c "import sys,json;print([k['kid'] for k in json.load(sys.stdin)['keys']])"
# The active kid is the NEW one — do a real login and check the access token header:
#   the JWT header (first dot-segment, base64url) should show \"kid\":\"dragonfly-id-2\".
# End-to-end: a fresh 'Sign in with Dragonfly' in any app must still succeed.
```

### Rollback / notes

- **If step 2 misbehaves,** revert `server/.env` to the pre-rotation values and
  `docker compose up -d --force-recreate server`. Because `old` was still the signer and still
  published, nothing was lost.
- **Suspected compromise (can't wait):** you may retire the old key immediately (skip the overlap)
  — the cost is that access tokens signed by the compromised key are rejected the moment it leaves
  JWKS, forcing at most one silent re-auth on the affected clients. Acceptable to cut the exposure.
- **Config guards:** the server refuses to boot if the secondary is half-set (only one of the
  pem/kid pair) or if `OIDC_SECONDARY_KEY_ID == OIDC_KEY_ID` — a duplicate-kid JWKS resolves
  ambiguously. Set both vars or neither, with distinct kids.
- The dev fallback (no `OIDC_PRIVATE_KEY` ⇒ throwaway key each boot) is unaffected; rotation only
  matters for the stable deployed key.

## Ports on this host

Spotter 8000/5432, Plate 8001/5433, posterizarr 8002, Cookbook 8003/5434 — dragonfly-id uses
**8004** (API) and **5435** (db).

## What the app servers need (Phase 2b, next)

Each app server will validate suite tokens against `https://id.dragonflymedia.org/.well-known/jwks.json`
(config `SUITE_JWKS_URL`), checking `iss` = ISSUER and `aud` = `suite`. Nothing to do here for that;
it's additive work in each app's own repo.
