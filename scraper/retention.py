"""Retention policy: periodically purge old availability rows.

The ``availability`` table grows unboundedly — one row per station per scrape
(every 5 minutes), which is roughly 30k rows/day. On the Supabase free tier the
database is capped at 0.5 GB, so old rows must be deleted on a schedule.

The purge runs once at container startup and then on a fixed interval via
:func:`retention_worker`. Deletion is done in small batches so each transaction
stays short and never holds a long lock on the table.
"""

from __future__ import annotations

import time
from datetime import timedelta

from sqlalchemy import delete, select

from config import (
    AVAILABILITY_RETENTION_DAYS,
    DELETE_OLD_AVAILABILITY_INTERVAL_SECONDS,
    RETRY_INTERVAL_SECONDS,
)
from database import SessionLocal
from models import Availability
from time_utils import format_log_ts, utc_now_naive

# Delete in batches to keep each transaction short. PostgreSQL has no
# ``DELETE ... LIMIT``, so we select a batch of ids first, then delete them.
_DELETE_BATCH_SIZE = 5000


def delete_old_availability(retention_days: int | None = None) -> int:
    """Delete availability rows older than the retention window.

    Returns the total number of rows deleted. ``retention_days`` defaults to
    ``AVAILABILITY_RETENTION_DAYS``. Rows are deleted in batches of
    ``_DELETE_BATCH_SIZE`` so no single transaction is too large.
    """
    retention_days = retention_days or AVAILABILITY_RETENTION_DAYS
    cutoff = utc_now_naive() - timedelta(days=retention_days)

    session = SessionLocal()
    total_deleted = 0
    try:
        while True:
            batch_ids = session.scalars(
                select(Availability.id)
                .where(Availability.timestamp < cutoff)
                .limit(_DELETE_BATCH_SIZE)
            ).all()
            if not batch_ids:
                break
            result = session.execute(
                delete(Availability).where(Availability.id.in_(batch_ids))
            )
            session.commit()
            total_deleted += result.rowcount
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()

    return total_deleted


def retention_worker() -> None:
    """Run the retention purge on a fixed interval (default 1 hour)."""
    while True:
        try:
            deleted = delete_old_availability()
            print(
                f"[{format_log_ts()}] Retention purge: deleted {deleted} "
                f"old availability rows"
            )
        except Exception as e:
            print(f"[{format_log_ts()}] Retention purge error: {e}")
        time.sleep(DELETE_OLD_AVAILABILITY_INTERVAL_SECONDS)
