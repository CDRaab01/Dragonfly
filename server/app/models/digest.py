"""Stored weekly digest (ROADMAP3 Tier W1).

The digest service aggregates the owner's week across the suite, narrates it, and upserts one row
per (owner, week). The hub reads the latest via GET /digest/weekly. Kept deliberately simple: the
numbers live as a JSON blob (each domain optional — a digest degrades to whatever it could reach)
and the narrative is nullable (omitted when LM Studio is unreachable).
"""

import uuid
from datetime import datetime, timezone

from sqlalchemy import DateTime, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


def _uuid() -> str:
    return str(uuid.uuid4())


def _now() -> datetime:
    return datetime.now(timezone.utc)


class WeeklyDigest(Base):
    __tablename__ = "weekly_digests"
    __table_args__ = (UniqueConstraint("owner_email", "week_start", name="uq_digest_owner_week"),)

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    owner_email: Mapped[str] = mapped_column(String(255), index=True)
    week_start: Mapped[str] = mapped_column(String(10))  # ISO date (Monday of the week)
    week_end: Mapped[str] = mapped_column(String(10))  # ISO date (Sunday)
    # The aggregated numbers per domain (training/nutrition/cooking/money), each nullable — JSON text.
    data_json: Mapped[str] = mapped_column(Text)
    # The LM-Studio narrative; null when the model was unreachable (the digest still shows numbers).
    narrative: Mapped[str | None] = mapped_column(Text, nullable=True)
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
