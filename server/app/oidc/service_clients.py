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


_CLIENTS: dict[str, str] = _parse(settings.cross_app_clients)


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
