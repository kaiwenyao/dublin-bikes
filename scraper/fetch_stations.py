#!/usr/bin/env python3
"""
Request Dublin bike station data via the JCDecaux API.
"""

import json
import urllib.parse
import urllib.request

from config import BASE_URL, OUTPUT_JSON, PARAMS


def fetch_stations():
    """Request station data and return a JSON list."""
    url = f"{BASE_URL}?{urllib.parse.urlencode(PARAMS)}"
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "dublin-bikes-scraper/1.0"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode())


if __name__ == "__main__":
    try:
        data = fetch_stations()
        print(f"Fetched {len(data)} stations")

        with open(OUTPUT_JSON, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"Data saved to {OUTPUT_JSON}")

        # Print first few as examples
        for i, station in enumerate(data[:3]):
            print(f"\n--- Station {i + 1} ---")
            print(station)
        if len(data) > 3:
            print(f"\n... {len(data) - 3} more stations")
    except urllib.error.URLError as e:
        print(f"Request failed: {e}")
