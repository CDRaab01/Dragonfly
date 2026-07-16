"""Narrate the week via LM Studio (ROADMAP3 Tier W1).

Descriptive only — the deterministic numbers are the truth; this is warm flavor over them. Server-
side prompt, retrospective, no advice. Returns ``None`` on any failure so the digest still ships
its numbers (the "degrade to just the numbers" rule the owner signed off on).
"""

import logging

import httpx

from app.config import settings

logger = logging.getLogger("digest.narrator")

_SYSTEM = (
    "You write a warm, concise weekly recap for one person across their personal apps: training, "
    "nutrition, cooking, and money. Two to four sentences, second person, encouraging but honest. "
    "Use ONLY the numbers provided — never invent data, and never give medical, financial, or "
    "investment advice. If a domain is missing, just skip it. Plain text: no markdown, no lists."
)


def _dollars(cents) -> str:
    try:
        return f"${cents / 100:,.0f}"
    except Exception:
        return "?"


def _facts(domains: dict) -> str:
    lines: list[str] = []
    t = domains.get("training")
    if t:
        tot = t.get("totals", {}) or {}
        lines.append(
            f"Training: trained {tot.get('days_trained', '?')} days "
            f"({tot.get('strength_sessions', '?')} strength, {tot.get('cardio_sessions', '?')} cardio)."
        )
    n = domains.get("nutrition")
    if n:
        wk = n.get("weight_change_kg")
        wt = f"{wk * 2.20462:+.1f} lb" if isinstance(wk, (int, float)) else "no weigh-ins"
        lines.append(
            f"Nutrition: logged {n.get('days_logged', '?')} days; calorie adherence "
            f"{n.get('calorie_adherence_pct', '?')}%, protein {n.get('protein_adherence_pct', '?')}%; "
            f"weight {wt}."
        )
    c = domains.get("cooking")
    if c:
        lines.append(
            f"Cooking: cooked {c.get('count', '?')} meals "
            f"({c.get('distinct_recipes', '?')} different recipes)."
        )
    m = domains.get("money")
    if m:
        lines.append(
            f"Money: net {_dollars(m.get('net_cents'))} "
            f"(income {_dollars(m.get('income_cents'))}, spend {_dollars(m.get('spend_cents'))})."
        )
    return "\n".join(lines)


async def narrate(domains: dict) -> str | None:
    facts = _facts(domains)
    if not facts.strip():
        return None
    payload = {
        "model": settings.lm_studio_model,
        "messages": [
            {"role": "system", "content": _SYSTEM},
            {"role": "user", "content": f"Here is my week:\n{facts}\n\nWrite my recap."},
        ],
        "temperature": 0.6,
        "max_tokens": 220,
    }
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            r = await client.post(
                f"{settings.lm_studio_base_url.rstrip('/')}/chat/completions", json=payload
            )
            if r.status_code == 200:
                return (r.json()["choices"][0]["message"]["content"] or "").strip() or None
            logger.warning("LM Studio narration returned %s", r.status_code)
    except Exception:
        logger.warning("LM Studio narration failed — digest shows numbers only", exc_info=True)
    return None
