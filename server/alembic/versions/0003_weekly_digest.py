"""weekly digest (ROADMAP3 Tier W1)

Adds `weekly_digests` — one narrated aggregate per (owner, week), read by the hub.

Revision ID: 0003
Revises: 0002
Create Date: 2026-07-16
"""

import sqlalchemy as sa
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "weekly_digests",
        sa.Column("id", sa.String(), primary_key=True),
        sa.Column("owner_email", sa.String(length=255), nullable=False),
        sa.Column("week_start", sa.String(length=10), nullable=False),
        sa.Column("week_end", sa.String(length=10), nullable=False),
        sa.Column("data_json", sa.Text(), nullable=False),
        sa.Column("narrative", sa.Text(), nullable=True),
        sa.Column("generated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.UniqueConstraint("owner_email", "week_start", name="uq_digest_owner_week"),
    )
    op.create_index("ix_weekly_digests_owner_email", "weekly_digests", ["owner_email"])


def downgrade() -> None:
    op.drop_index("ix_weekly_digests_owner_email", "weekly_digests")
    op.drop_table("weekly_digests")
