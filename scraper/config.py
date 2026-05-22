"""
Standalone scraper config: only depends on environment variables, no Flask dependency.
"""

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(dotenv_path=Path(__file__).resolve().parent / ".env")

BASE_URL = os.environ.get("JCDECAUX_BASE_URL", "https://api.jcdecaux.com/vls/v1/stations")
PARAMS = {
    "contract": os.environ.get("JCDECAUX_CONTRACT", "dublin"),
    "apiKey": os.environ.get("JCDECAUX_API_KEY", ""),
}

OUTPUT_JSON = os.environ.get("OUTPUT_JSON", "stations.json")

DATABASE_URL = os.environ.get("DATABASE_URL")
if not DATABASE_URL:
    raise ValueError("DATABASE_URL environment variable is not set. Please configure it in the .env file.")

SCRAPE_INTERVAL_SECONDS = int(os.environ.get("SCRAPE_INTERVAL_SECONDS", "300"))
RETRY_INTERVAL_SECONDS = int(os.environ.get("RETRY_INTERVAL_SECONDS", "60"))

OPENWEATHER_API_KEY = os.environ.get("OPENWEATHER_API_KEY")

# For Geocoding API: target city
WEATHER_CITY = os.environ.get("WEATHER_CITY", "Dublin,IE")

# OpenWeather API URLs
OPENWEATHER_GEOCODING_URL = os.environ.get("OPENWEATHER_GEOCODING_URL", "http://api.openweathermap.org/geo/1.0/direct")
OPENWEATHER_ONECALL_URL = os.environ.get("OPENWEATHER_ONECALL_URL", "https://api.openweathermap.org/data/3.0/onecall")
OPENWEATHER_FORECAST_URL = os.environ.get("OPENWEATHER_FORECAST_URL", "https://api.openweathermap.org/data/2.5/forecast")

# For Weather: polling interval is usually once per hour (3600 seconds)
# We store this independently from the station polling interval.
WEATHER_SCRAPE_INTERVAL_SECONDS = int(os.environ.get("WEATHER_SCRAPE_INTERVAL_SECONDS", "3600"))
