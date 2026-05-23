"""UTC time helpers for scraper storage, logging, and API parsing."""

from __future__ import annotations

from datetime import datetime, timezone

UTC = timezone.utc


def utc_now() -> datetime:
    """Current instant as timezone-aware UTC."""
    return datetime.now(UTC)


def utc_now_naive() -> datetime:
    """Naive UTC wall time for DATETIME columns (store UTC, no DB tz conversion)."""
    return utc_now().replace(tzinfo=None)


def from_unix_ms_utc(epoch_ms: int | float) -> datetime:
    """JCDecaux `last_update` (epoch ms) -> naive UTC for storage."""
    return datetime.fromtimestamp(epoch_ms / 1000.0, tz=UTC).replace(tzinfo=None)


def from_unix_s_utc(epoch_s: int | float) -> datetime:
    """OpenWeather `dt` (epoch seconds) -> naive UTC for storage."""
    return datetime.fromtimestamp(epoch_s, tz=UTC).replace(tzinfo=None)


def floor_hour_utc_naive(dt: datetime | None = None) -> datetime:
    """Start of the current UTC hour as naive UTC (for DB comparisons)."""
    aware = utc_now() if dt is None else (dt.replace(tzinfo=UTC) if dt.tzinfo is None else dt.astimezone(UTC))
    return aware.replace(minute=0, second=0, microsecond=0).replace(tzinfo=None)


def format_log_ts(dt: datetime | None = None) -> str:
    """ISO-8601 UTC for logs, e.g. 2026-05-23T09:45:12Z."""
    aware = utc_now() if dt is None else (dt.replace(tzinfo=UTC) if dt.tzinfo is None else dt.astimezone(UTC))
    return aware.strftime("%Y-%m-%dT%H:%M:%SZ")
