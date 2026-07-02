"""Initial identity tables: users, oauth_authorization_codes, oauth_refresh_tokens.

Revision ID: 0001
Revises:
Create Date: 2026-07-02
"""

import sqlalchemy as sa

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("email", sa.String(320), nullable=False),
        sa.Column("name", sa.String(200), nullable=False, server_default=""),
        sa.Column("password_hash", sa.String(255), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("reset_token_hash", sa.String(255), nullable=True),
        sa.Column("reset_token_expires_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_users_email", "users", ["email"], unique=True)

    op.create_table(
        "oauth_authorization_codes",
        sa.Column("code", sa.String(64), primary_key=True),
        sa.Column("client_id", sa.String(64), nullable=False),
        sa.Column("redirect_uri", sa.String(512), nullable=False),
        sa.Column("user_id", sa.String(36), nullable=False),
        sa.Column("scope", sa.String(256), nullable=False, server_default="openid"),
        sa.Column("nonce", sa.String(256), nullable=True),
        sa.Column("code_challenge", sa.String(256), nullable=False),
        sa.Column("code_challenge_method", sa.String(8), nullable=False, server_default="S256"),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
    )

    op.create_table(
        "oauth_refresh_tokens",
        sa.Column("token", sa.String(128), primary_key=True),
        sa.Column("user_id", sa.String(36), nullable=False),
        sa.Column("client_id", sa.String(64), nullable=False),
        sa.Column("scope", sa.String(256), nullable=False, server_default="openid"),
        sa.Column("revoked", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
    )


def downgrade() -> None:
    op.drop_table("oauth_refresh_tokens")
    op.drop_table("oauth_authorization_codes")
    op.drop_index("ix_users_email", table_name="users")
    op.drop_table("users")
