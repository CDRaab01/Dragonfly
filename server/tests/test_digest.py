"""Weekly digest (Tier W1) — generation, storage, read auth, and week math."""

import datetime

from app.digest import service as digest_service

_KEY = {"X-Digest-Key": "test-digest-key"}


async def _fake_aggregate(start, end):
    return {
        "training": {"totals": {"days_trained": 4, "strength_sessions": 3, "cardio_sessions": 1}},
        "nutrition": {
            "days_logged": 6,
            "calorie_adherence_pct": 83,
            "protein_adherence_pct": 71,
            "weight_change_kg": -0.3,
        },
        "cooking": {"count": 5, "distinct_recipes": 4},
        "money": {"net_cents": 318000, "income_cents": 450000, "spend_cents": -132000},
    }


async def _fake_narrate(domains):
    return "Strong week — four training days and five home-cooked meals."


async def test_generate_then_read(client, monkeypatch):
    monkeypatch.setattr("app.digest.service.aggregate_week", _fake_aggregate)
    monkeypatch.setattr("app.digest.service.narrate", _fake_narrate)

    # No key → unauthorized.
    assert (await client.get("/digest/weekly")).status_code == 401

    # Generate this week's digest.
    r = await client.post("/digest/generate", headers=_KEY)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["domains"]["training"]["totals"]["days_trained"] == 4
    assert body["narrative"].startswith("Strong week")

    # Read the latest — same data.
    r = await client.get("/digest/weekly", headers=_KEY)
    assert r.status_code == 200
    got = r.json()
    assert got["domains"]["cooking"]["count"] == 5
    assert got["domains"]["money"]["net_cents"] == 318000
    assert got["week_start"] <= got["week_end"]


async def test_wrong_key_is_401(client):
    assert (await client.get("/digest/weekly", headers={"X-Digest-Key": "nope"})).status_code == 401


async def test_degrades_when_narration_missing(client, monkeypatch):
    monkeypatch.setattr("app.digest.service.aggregate_week", _fake_aggregate)
    monkeypatch.setattr("app.digest.service.narrate", lambda domains: _none())  # narration down
    r = await client.post("/digest/generate", headers=_KEY)
    assert r.status_code == 200
    # Numbers survive; narrative is simply omitted.
    assert r.json()["narrative"] is None
    assert r.json()["domains"]["nutrition"]["days_logged"] == 6


async def _none():
    return None


def test_week_bounds_is_a_completed_mon_to_sun():
    for d in (
        datetime.date(2026, 7, 13),
        datetime.date(2026, 7, 15),
        datetime.date(2026, 7, 19),
        datetime.date(2026, 7, 20),
    ):
        start, end = digest_service.week_bounds(d)
        assert end.weekday() == 6  # Sunday
        assert start.weekday() == 0  # Monday
        assert (end - start).days == 6
        assert end <= d and (d - end).days < 7
