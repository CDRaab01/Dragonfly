from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_version():
    r = client.get("/version")
    assert r.status_code == 200
    body = r.json()
    assert body["name"] == "Dragonfly ID"
    assert body["version"] == "0.1.0"
