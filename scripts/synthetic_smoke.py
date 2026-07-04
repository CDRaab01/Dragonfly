#!/usr/bin/env python3
"""
Post-deploy smoke for dragonfly-id (the suite OIDC identity server).

Unlike the CRUD apps, dragonfly-id's job is to serve valid OIDC metadata + signing keys that
every app server's /auth/suite depends on. So the smoke proves: the process is up (/health),
discovery is served with an issuer + jwks_uri, and the JWKS has at least one key with a `kid`.
A /token round-trip needs a full auth-code + PKCE dance and a session, out of scope for a smoke.
"""
import urllib.request
import urllib.error
import json
import os
import sys

BASE = os.environ.get("DRAGONFLY_ID_URL", "http://127.0.0.1:8004")

def get(path):
    try:
        with urllib.request.urlopen(BASE + path, timeout=30) as r:
            body = r.read()
            try:
                return r.getcode(), json.loads(body)
            except json.JSONDecodeError:
                return r.getcode(), body.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception as e:
        return None, str(e)

def main():
    s, _ = get("/health")
    if s != 200:
        print(f"[FAIL] health: HTTP {s}"); sys.exit(1)
    print("[ok] health")

    s, disc = get("/.well-known/openid-configuration")
    if s != 200 or not isinstance(disc, dict) or "issuer" not in disc or "jwks_uri" not in disc:
        print(f"[FAIL] discovery: HTTP {s} {str(disc)[:200]}"); sys.exit(1)
    print(f"[ok] discovery (issuer={disc['issuer']})")

    s, jwks = get("/.well-known/jwks.json")
    if s != 200 or not isinstance(jwks, dict) or not jwks.get("keys"):
        print(f"[FAIL] jwks: HTTP {s} {str(jwks)[:200]}"); sys.exit(1)
    if not any(isinstance(k, dict) and k.get("kid") for k in jwks["keys"]):
        print("[FAIL] jwks: no key with a kid"); sys.exit(1)
    print(f"[ok] jwks ({len(jwks['keys'])} key(s))")

    print("SMOKE_PASS")
    sys.exit(0)

if __name__ == "__main__":
    main()
