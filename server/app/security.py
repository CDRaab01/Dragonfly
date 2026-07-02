from passlib.context import CryptContext

# argon2id for the suite identity store (a fresh service — no legacy bcrypt hashes to carry).
pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)
