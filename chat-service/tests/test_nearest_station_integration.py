"""Opt-in integration test: executes the real nearest-station SQL.

Skipped unless CHAT_DB_URL is set in the environment (read-only SELECT).
"""
import math
import os

import pytest

from main import ChatRequest, Settings, get_nearest_station_availability

requires_db = pytest.mark.skipif(
    not os.environ.get("CHAT_DB_URL"),
    reason="CHAT_DB_URL not set; integration test runs only against a real database",
)

@requires_db
def test_nearest_station_query_executes_and_ranks():
    settings = Settings(_env_file=None, CHAT_DB_URL=os.environ["CHAT_DB_URL"], DEEPSEEK_API_KEY="unused")
    req = ChatRequest(
        session_id="integration-test",
        user_id=0,
        message="nearest station",
        location={"lat": 53.3498, "lng": -6.2603},
    )

    result = get_nearest_station_availability(req, settings, limit=5)

    stations = result["stations"]
    assert stations, "expected at least one station row"
    distances = [s["distance_m"] for s in stations]
    assert distances == sorted(distances)
    assert all(isinstance(d, int) for d in distances)
    # Parity: recompute Haversine in Python from the returned coordinates.
    for s in stations:
        lat1, lng1 = math.radians(53.3498), math.radians(-6.2603)
        lat2, lng2 = math.radians(s["latitude"]), math.radians(s["longitude"])
        a = (
            math.sin((lat2 - lat1) / 2) ** 2
            + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
        )
        expected = 6_371_000 * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
        assert abs(s["distance_m"] - expected) <= 2
