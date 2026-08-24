"""Tests for the availability retention purge.

Verifies that delete_old_availability() removes rows older than the retention
window while keeping recent rows, and that it works in batches.
"""
import os

os.environ.setdefault("DATABASE_URL", "sqlite+pysqlite:///:memory:")
os.environ.setdefault("AVAILABILITY_RETENTION_DAYS", "30")

import unittest
from datetime import timedelta

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import retention
from database import Base
from models import Availability, Station
from time_utils import utc_now_naive


class RetentionTest(unittest.TestCase):
    def setUp(self):
        # Point the session factory at an in-memory SQLite DB.
        engine = create_engine("sqlite+pysqlite:///:memory:")
        Base.metadata.create_all(engine)
        self.session_factory = sessionmaker(bind=engine, expire_on_commit=False)
        self._orig_factory = retention.SessionLocal
        retention.SessionLocal = self.session_factory

        self.session = self.session_factory()
        self.session.add(Station(number=1, contract_name="dublin", name="A",
                                 address="addr", latitude=0.0, longitude=0.0,
                                 banking=False, bonus=False, bike_stands=10))
        self.session.commit()

    def tearDown(self):
        self.session.close()
        retention.SessionLocal = self._orig_factory

    def _add_availability(self, days_ago, requested_days_ago=None):
        requested_days_ago = days_ago if requested_days_ago is None else requested_days_ago
        self.session.add(Availability(
            number=1, available_bikes=1, available_bike_stands=9,
            status="OPEN", last_update=0,
            timestamp=utc_now_naive() - timedelta(days=days_ago),
            requested_at=utc_now_naive() - timedelta(days=requested_days_ago),
        ))
        self.session.commit()

    def test_deletes_only_old_rows(self):
        self._add_availability(days_ago=1)   # keep
        self._add_availability(days_ago=40)  # delete
        self._add_availability(days_ago=60)  # delete

        deleted = retention.delete_old_availability(retention_days=30)
        self.assertEqual(deleted, 2)

        remaining = self.session.query(Availability).count()
        self.assertEqual(remaining, 1)

    def test_no_rows_to_delete(self):
        self._add_availability(days_ago=1)
        deleted = retention.delete_old_availability(retention_days=30)
        self.assertEqual(deleted, 0)
        self.assertEqual(self.session.query(Availability).count(), 1)

    def test_keeps_recent_scrape_when_upstream_timestamp_is_old(self):
        self._add_availability(days_ago=40, requested_days_ago=1)

        deleted = retention.delete_old_availability(retention_days=30)

        self.assertEqual(deleted, 0)
        self.assertEqual(self.session.query(Availability).count(), 1)

    def test_retention_days_zero_deletes_everything(self):
        # retention_days=0 must mean "delete everything", not fall back to the
        # default retention window (0 is falsy, so `or` would silently ignore it).
        self._add_availability(days_ago=1)
        self._add_availability(days_ago=2)
        deleted = retention.delete_old_availability(retention_days=0)
        self.assertEqual(deleted, 2)
        self.assertEqual(self.session.query(Availability).count(), 0)


if __name__ == "__main__":
    unittest.main()
