from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from config import DATABASE_URL


class Base(DeclarativeBase):
    pass


engine = create_engine(DATABASE_URL, pool_pre_ping=True)


@event.listens_for(engine, "connect")
def _set_connection_utc(dbapi_connection, connection_record) -> None:
    """Pin session timezone to UTC so drivers never reinterpret naive UTC values."""
    dialect = connection_record.dialect.name
    cursor = dbapi_connection.cursor()
    try:
        if dialect == "postgresql":
            cursor.execute("SET TIME ZONE 'UTC'")
        elif dialect in ("mysql", "mariadb"):
            cursor.execute("SET time_zone = '+00:00'")
    finally:
        cursor.close()


SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)
