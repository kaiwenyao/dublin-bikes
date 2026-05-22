#!/usr/bin/env python3
import datetime
import time

from config import RETRY_INTERVAL_SECONDS, SCRAPE_INTERVAL_SECONDS, WEATHER_SCRAPE_INTERVAL_SECONDS
from database import SessionLocal
from fetch_stations import fetch_stations
from fetch_weather import fetch_weather_and_store
from models import Availability, Station


def scrape_stations():
    """
    Scrape station data. Expects the API response format to match the example, e.g.:
    {
      "number": 42,
      "contract_name": "dublin",
      "name": "SMITHFIELD NORTH",
      "address": "Smithfield North",
      "position": {"lat": 53.349562, "lng": -6.278198},
      "banking": false,
      "bonus": false,
      "bike_stands": 30,
      "available_bike_stands": 2,
      "available_bikes": 28,
      "status": "OPEN",
      "last_update": 1770053684000
    }
    """
    started_at = datetime.datetime.now()
    print(f"[{started_at.strftime('%Y-%m-%d %H:%M:%S')}] Starting scrape...")

    raw = fetch_stations()
    # Compatibility: return list directly, or wrapped in {"stations": [...]} etc.
    if isinstance(raw, list):
        data = raw
    elif isinstance(raw, dict):
        data = raw.get("stations", raw.get("data", []))
    else:
        data = []
    total = len(data)

    session = SessionLocal()
    try:
        new_stations = 0
        for item in data:
            # --- Step 1: Process Station (static data) ---
            station = session.get(Station, item["number"])

            if not station:
                new_stations += 1
                station = Station(
                    number=item["number"],
                    contract_name=item["contract_name"],
                    name=item["name"],
                    address=item["address"],
                    latitude=item["position"]["lat"],
                    longitude=item["position"]["lng"],
                    banking=item["banking"],
                    bonus=item["bonus"],
                    bike_stands=item["bike_stands"],
                )
                session.add(station)

            # --- Step 2: Process Availability (dynamic data) ---
            dt_object = datetime.datetime.fromtimestamp(item["last_update"] / 1000.0)
            availability = Availability(
                number=item["number"],
                available_bikes=item["available_bikes"],
                available_bike_stands=item["available_bike_stands"],
                status=item["status"],
                last_update=item["last_update"],
                timestamp=dt_object,
                requested_at=datetime.datetime.now(datetime.timezone.utc),
            )
            session.add(availability)

        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()

    finished_at = datetime.datetime.now()
    duration_sec = (finished_at - started_at).total_seconds()
    print(
        f"[{finished_at.strftime('%Y-%m-%d %H:%M:%S')}] Done | "
        f"Fetched {total} station records, {new_stations} new stations, wrote {total} availability records | "
        f"Elapsed: {duration_sec:.2f}s"
    )


import threading

def weather_worker():
    """
    Runs weather scraping in an independent thread at a fixed interval (1 hour).
    """
    while True:
        try:
            fetch_weather_and_store()
            time.sleep(WEATHER_SCRAPE_INTERVAL_SECONDS)
        except Exception as e:
            ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            print(f"[{ts}] Weather thread error: {e}")
            time.sleep(RETRY_INTERVAL_SECONDS)

# Main loop (table schema is maintained by flask-app migrations; run `flask db upgrade` first)
if __name__ == "__main__":
    # Start weather scraping thread
    t_weather = threading.Thread(target=weather_worker, daemon=True)
    t_weather.start()

    while True:
        try:
            scrape_stations()
            # Default rest interval is 5 minutes, can be overridden via .env
            time.sleep(SCRAPE_INTERVAL_SECONDS)
        except Exception as e:
            ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            print(f"[{ts}] Station scrape error: {e}")
            time.sleep(RETRY_INTERVAL_SECONDS)
