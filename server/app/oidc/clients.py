"""First-party suite OIDC clients.

All are **public** clients (no secret) using authorization-code + PKCE — the right profile for
native apps and SPAs, which can't hold a secret. For a private single-tenant suite a static
registry in code is simpler and safer than a DB of clients. Redirect URIs are matched **exactly**.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Client:
    client_id: str
    redirect_uris: tuple[str, ...]


CLIENTS: dict[str, Client] = {
    # Android apps use AppAuth with a custom-scheme redirect.
    "spotter": Client("spotter", ("com.spotter:/oauth2redirect",)),
    "plate": Client("plate", ("com.plate:/oauth2redirect",)),
    "cookbook": Client("cookbook", ("com.cookbook:/oauth2redirect",)),
    "dragonfly": Client("dragonfly", ("com.dragonfly:/oauth2redirect",)),
    "magpie": Client("magpie", ("com.magpie:/oauth2redirect",)),
    # Local development + automated tests (loopback redirect, per RFC 8252).
    "localdev": Client("localdev", ("http://localhost:8100/callback", "http://127.0.0.1/callback")),
}


def get_client(client_id: str) -> Client | None:
    return CLIENTS.get(client_id)


def redirect_uri_registered(client: Client, redirect_uri: str) -> bool:
    return redirect_uri in client.redirect_uris
