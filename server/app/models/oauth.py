import uuid
from datetime import datetime, timezone

from sqlalchemy import Boolean, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _uuid() -> str:
    return str(uuid.uuid4())


class AuthorizationCode(Base):
    """Single-use, short-lived code from /authorize, redeemed once at /token with the PKCE verifier."""

    __tablename__ = "oauth_authorization_codes"

    code: Mapped[str] = mapped_column(String(64), primary_key=True)
    client_id: Mapped[str] = mapped_column(String(64), nullable=False)
    redirect_uri: Mapped[str] = mapped_column(String(512), nullable=False)
    user_id: Mapped[str] = mapped_column(String(36), nullable=False)
    scope: Mapped[str] = mapped_column(String(256), nullable=False, default="openid")
    nonce: Mapped[str | None] = mapped_column(String(256), nullable=True)
    code_challenge: Mapped[str] = mapped_column(String(256), nullable=False)
    code_challenge_method: Mapped[str] = mapped_column(String(8), nullable=False, default="S256")
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class RefreshToken(Base):
    """Opaque refresh token, stored so it can be revoked/rotated (unlike the stateless access JWT)."""

    __tablename__ = "oauth_refresh_tokens"

    token: Mapped[str] = mapped_column(String(128), primary_key=True)
    # Stable surrogate handle so the self-service account page can reference a session for
    # revocation WITHOUT ever putting the secret token value into the rendered HTML.
    id: Mapped[str] = mapped_column(String(36), unique=True, index=True, default=_uuid)
    user_id: Mapped[str] = mapped_column(String(36), nullable=False)
    client_id: Mapped[str] = mapped_column(String(64), nullable=False)
    scope: Mapped[str] = mapped_column(String(256), nullable=False, default="openid")
    revoked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
