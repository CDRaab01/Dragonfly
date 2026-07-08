"""Confidential service clients for cross-app tokens (ROADMAP T2 #5).

Distinct from the OIDC `clients.py` (public, PKCE, no secret): these are the suite's *backend*
servers (plate, cookbook, spotter) authenticating machine-to-machine at POST /cross-app/token to
obtain an RS256 cross-app token. Credentials come from `CROSS_APP_CLIENTS` in the environment as a
`client_id:secret` list; comparison is constant-time. An empty setting disables the endpoint.
"""

import secrets

from app.config import settings


def _parse(raw: str) -> dict[str, str]:
    clients: dict[str, str] = {}
    for entry in raw.replace(",", " ").split():
        client_id, sep, secret = entry.partition(":")
        if not sep or not client_id or not secret:
            raise ValueError(
                f"CROSS_APP_CLIENTS entry {entry!r} is malformed — expected `client_id:secret`."
            )
        clients[client_id] = secret
    return clients


def _parse_emails(raw: str) -> set[str]:
    """Space/comma-separated email allowlist, normalized to lowercase for case-insensitive match."""
    return {e.strip().lower() for e in raw.replace(",", " ").split() if e.strip()}


_CLIENTS: dict[str, str] = _parse(settings.cross_app_clients)
_SMOKE_CLIENTS: dict[str, str] = _parse(settings.smoke_clients)
_SMOKE_SUBJECTS: set[str] = _parse_emails(settings.smoke_subject_emails)


def cross_app_enabled() -> bool:
    """Whether any confidential client is configured (the endpoint 404s otherwise)."""
    return bool(_CLIENTS)


def verify_service_client(client_id: str, client_secret: str) -> bool:
    """Constant-time check of a client's credentials. False for unknown clients or bad secrets."""
    expected = _CLIENTS.get(client_id)
    if expected is None:
        # Still burn a comparison so timing doesn't distinguish unknown-client from bad-secret.
        secrets.compare_digest(client_secret, client_secret)
        return False
    return secrets.compare_digest(client_secret, expected)


def smoke_enabled() -> bool:
    """Whether any smoke client is configured (POST /smoke/token 404s otherwise)."""
    return bool(_SMOKE_CLIENTS)


def verify_smoke_client(client_id: str, client_secret: str) -> bool:
    """Same constant-time-or-nothing check as verify_service_client, over the separate
    smoke-client list — deliberately not the same dict, so a smoke credential can never be
    reused to mint a cross-app token or vice versa."""
    expected = _SMOKE_CLIENTS.get(client_id)
    if expected is None:
        secrets.compare_digest(client_secret, client_secret)
        return False
    return secrets.compare_digest(client_secret, expected)


def smoke_subject_allowed(email: str) -> bool:
    """F2: the smoke credential may only mint a suite token for a pre-designated throwaway email,
    never an arbitrary account. Fail-closed — an empty allowlist denies everyone, so a
    misconfigured deploy breaks the smoke test rather than reopening the impersonation hole."""
    return email.strip().lower() in _SMOKE_SUBJECTS
