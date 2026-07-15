"""Add a surrogate id to oauth_refresh_tokens for safe session-revoke handles.

The self-service account page lists a user's active sessions and revokes them. It must reference a
session without exposing the secret token value in the rendered HTML, so each refresh token gains a
stable surrogate ``id``. Existing rows are backfilled with generated UUIDs.

Revision ID: 0002
Revises: 0001
Create Date: 2026-07-15
"""

import sqlalchemy as sa

from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "oauth_refresh_tokens",
        sa.Column("id", sa.String(36), nullable=True),
    )
    # Backfill existing rows with unique UUIDs (gen_random_uuid is core in Postgres 13+).
    op.execute("UPDATE oauth_refresh_tokens SET id = gen_random_uuid()::text WHERE id IS NULL")
    op.alter_column("oauth_refresh_tokens", "id", nullable=False)
    op.create_index(
        "ix_oauth_refresh_tokens_id", "oauth_refresh_tokens", ["id"], unique=True
    )


def downgrade() -> None:
    op.drop_index("ix_oauth_refresh_tokens_id", table_name="oauth_refresh_tokens")
    op.drop_column("oauth_refresh_tokens", "id")
