<#
.SYNOPSIS
  Redeploy the dragonfly-id server from the canonical deployment clone (Windows / Docker Desktop).

.DESCRIPTION
  Pulls the requested ref, rebuilds the server image, restarts the stack, and waits for the API to
  report healthy. Idempotent and safe to re-run. Single source of redeploy logic — both the Deploy
  workflow and a human at the keyboard call it, so automated and manual deploys behave identically.

  Operates on the real deployment clone (which owns server/.env, the OIDC key, and the pgdata
  volume), never a runner's ephemeral checkout. .env is gitignored and pgdata is a named volume, so
  neither is touched by `git reset --hard`. Migrations run on container boot (entrypoint ->
  alembic upgrade head). COMPOSE_PROFILES=tunnel in the root .env keeps cloudflared in the set.

.PARAMETER Ref
  Commit SHA or branch to deploy. Defaults to origin/main. Pass a prior SHA to roll back.
#>
[CmdletBinding()]
param(
  [string]$Ref = "origin/main",
  [string]$HealthUrl = "http://127.0.0.1:8004/health",
  [int]$TimeoutSeconds = 120,
  [int]$FailureLogLines = 100
)

$ErrorActionPreference = "Stop"

# Repo root = parent of this script's directory (deploy/).
$RepoDir = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
  param([string]$Exe, [string[]]$ArgList)
  Write-Host "> $Exe $($ArgList -join ' ')"
  & $Exe @ArgList
  if ($LASTEXITCODE -ne 0) {
    throw "Command failed ($LASTEXITCODE): $Exe $($ArgList -join ' ')"
  }
}

Write-Host "=== dragonfly-id redeploy ==="
Write-Host "Repo:   $RepoDir"
Write-Host "Ref:    $Ref"

# Git refuses to operate on a repo owned by a different account than the one running it
# (CVE-2022-24765) — the case when the runner's service account redeploys an interactive user's
# clone. --global self-heals under whichever account runs the script, no admin step.
& git config --global --add safe.directory $RepoDir 2>$null

Invoke-Checked git @("-C", $RepoDir, "fetch", "--prune", "origin")
Invoke-Checked git @("-C", $RepoDir, "reset", "--hard", $Ref)
$deployedSha = (& git -C $RepoDir rev-parse --short HEAD).Trim()
Write-Host "Deployed commit: $deployedSha"

# Stamp the build so GET /version reports what's running.
$env:GIT_SHA = $deployedSha
$env:BUILT_AT = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
Invoke-Checked docker @("compose", "--project-directory", $RepoDir, "up", "-d", "--build", "--remove-orphans")

Write-Host "Waiting for $HealthUrl (timeout ${TimeoutSeconds}s)..."
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$healthy = $false
while ((Get-Date) -lt $deadline) {
  try {
    $resp = Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 5
    if ($resp.status -eq "ok") { $healthy = $true; break }
  } catch {
    # not up yet
  }
  Start-Sleep -Seconds 3
}
if (-not $healthy) {
  Write-Host "--- docker compose logs (last ${FailureLogLines} lines) ---"
  & docker compose --project-directory $RepoDir logs --no-color --tail $FailureLogLines 2>$null
  throw "Health check failed: $HealthUrl did not report ok within ${TimeoutSeconds}s."
}
Write-Host "Health check passed."

# Informational: /health only proves the API is up locally, not that Cloudflare can reach it.
$cfId = (& docker compose --project-directory $RepoDir ps -q cloudflared 2>$null)
if ($cfId) {
  $cfHealth = (& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}no healthcheck{{end}}' $cfId 2>$null)
  Write-Host "Tunnel (cloudflared) health: $cfHealth"
}

Invoke-Checked docker @("image", "prune", "-f")
Write-Host "=== Redeploy complete ($deployedSha) ==="
