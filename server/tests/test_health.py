async def test_health(client):
    r = await client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


async def test_version(client):
    r = await client.get("/version")
    assert r.status_code == 200
    body = r.json()
    assert body["name"] == "Dragonfly ID"
    assert body["version"] == "0.1.0"
