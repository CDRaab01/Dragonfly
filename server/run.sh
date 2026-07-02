#!/usr/bin/env bash
# Local dev runner: apply migrations then serve with reload. Expects a .env (see .env.example)
# and a reachable Postgres.
set -euo pipefail
alembic upgrade head
exec uvicorn app.main:app --reload --port "${PORT:-8010}"
