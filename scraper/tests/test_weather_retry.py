"""Regression test: weather scrape errors must propagate so the worker retries.

main_scraper.weather_worker() wraps fetch_weather_and_store() with an
except-branch that sleeps RETRY_INTERVAL_SECONDS (60s). For that branch to be
reachable, fetch_weather_and_store() must re-raise instead of swallowing every
exception. This test pins that contract.
"""
import os

os.environ.setdefault("DATABASE_URL", "sqlite+pysqlite:///:memory:")
os.environ.setdefault("OPENWEATHER_API_KEY", "test-dummy-key")

import unittest
from unittest import mock

import requests  # noqa: E402  (after env setup)

import fetch_weather  # noqa: E402


class FetchWeatherPropagatesErrorsTest(unittest.TestCase):
    @mock.patch("fetch_weather.requests.get")
    def test_network_failure_is_not_swallowed(self, mock_get):
        mock_get.side_effect = requests.exceptions.ConnectionError("api down")
        with self.assertRaises(fetch_weather.WeatherScraperError):
            fetch_weather.fetch_weather_and_store()


if __name__ == "__main__":
    unittest.main()
