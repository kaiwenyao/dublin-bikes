"""UTC time helpers for the Dublin Bikes scraper.

Convention
----------
- **DB columns (naive DATETIME):** calendar fields are UTC wall time — use
  ``utc_now_naive()`` or ``from_unix_*_utc()`` only at write boundaries.
- **Logs and duration math:** timezone-aware UTC — use ``utc_now()`` and pass
  that into ``format_log_ts()`` / ``floor_hour_utc_naive(dt=...)``.

Naive datetimes from ``datetime.now()`` or third-party code must not be passed
into helpers that interpret an *instant*; they are rejected so local wall time
is never silently treated as UTC.
"""

from __future__ import annotations

from datetime import datetime, timezone

UTC = timezone.utc


def _as_utc_aware(dt: datetime) -> datetime:
    """Return ``dt`` normalized to UTC; reject naive (ambiguous timezone)."""
    if dt.tzinfo is None:
        raise ValueError(
            "naive datetime is not allowed here — use utc_now() for aware UTC, "
            "or utc_now_naive()/from_unix_*_utc() only when writing to the database"
        )
    return dt.astimezone(UTC)


def utc_now() -> datetime:
    """Current instant as timezone-aware UTC."""
    return datetime.now(UTC)


def utc_now_naive() -> datetime:
    """Naive UTC wall time for DATETIME columns (store UTC, no DB tz conversion)."""
    return utc_now().replace(tzinfo=None)


def from_unix_ms_utc(epoch_ms: int | float) -> datetime:
    """JCDecaux ``last_update`` (epoch ms) -> naive UTC for storage."""
    return datetime.fromtimestamp(epoch_ms / 1000.0, tz=UTC).replace(tzinfo=None)


def from_unix_s_utc(epoch_s: int | float) -> datetime:
    """OpenWeather ``dt`` (epoch seconds) -> naive UTC for storage."""
    return datetime.fromtimestamp(epoch_s, tz=UTC).replace(tzinfo=None)


def floor_hour_utc_naive(dt: datetime | None = None) -> datetime:
    """Start of the UTC hour as naive UTC (for DB comparisons).

    If ``dt`` is given, it must be timezone-aware (typically from ``utc_now()``).
    """
    aware = utc_now() if dt is None else _as_utc_aware(dt)
    return aware.replace(minute=0, second=0, microsecond=0).replace(tzinfo=None)


def format_log_ts(dt: datetime | None = None) -> str:
    """ISO-8601 UTC for logs, e.g. ``2026-05-23T09:45:12Z``.

    If ``dt`` is given, it must be timezone-aware (typically from ``utc_now()``).
    """
    aware = utc_now() if dt is None else _as_utc_aware(dt)
    return aware.strftime("%Y-%m-%dT%H:%M:%SZ")
