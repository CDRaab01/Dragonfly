from slowapi import Limiter
from slowapi.util import get_remote_address

from app.config import settings


def _client_key(request):
    # Behind a trusted proxy (Cloudflare Tunnel), prefer the real client IP; otherwise the socket
    # peer. Only trust forwarded headers when explicitly enabled, or clients can spoof the key.
    if settings.trust_proxy:
        fwd = request.headers.get("cf-connecting-ip") or request.headers.get("x-forwarded-for")
        if fwd:
            return fwd.split(",")[0].strip()
    return get_remote_address(request)


limiter = Limiter(key_func=_client_key)
