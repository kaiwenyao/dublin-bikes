import requests
from sqlalchemy import delete

from config import OPENWEATHER_API_KEY, WEATHER_CITY, OPENWEATHER_GEOCODING_URL, OPENWEATHER_ONECALL_URL, OPENWEATHER_FORECAST_URL
from database import SessionLocal
from models_weather import WeatherForecast
from time_utils import floor_hour_utc_naive, format_log_ts, from_unix_s_utc, utc_now, utc_now_naive


class WeatherScraperError(Exception):
    pass


def get_lat_lon_for_city(city: str) -> tuple[float, float]:
    """
    Get latitude and longitude for a city via the Geocoding API.
    """
    url = OPENWEATHER_GEOCODING_URL
    params = {
        "q": city,
        "limit": 1,
        "appid": OPENWEATHER_API_KEY,
    }

    try:
        response = requests.get(url, params=params, timeout=10)
        response.raise_for_status()
        data = response.json()
        if not data:
            raise WeatherScraperError(f"Geocoding API returned empty data for city: {city}")
        
        return data[0]["lat"], data[0]["lon"]
    except requests.exceptions.RequestException as e:
        raise WeatherScraperError(f"Failed to fetch geocoding data: {e}")


def fetch_weather_and_store():
    """
    Fetch weather forecast for the target city and store it in the database (updates the next 2 days, i.e., 48 hours).
    """
    if not OPENWEATHER_API_KEY:
        print("OPENWEATHER_API_KEY is not configured, skipping weather scrape.")
        return

    started_at = utc_now()
    print(f"[{format_log_ts(started_at)}] Starting weather scrape ({WEATHER_CITY})...")

    try:
        # 1. Dynamically get city latitude and longitude
        lat, lon = get_lat_lon_for_city(WEATHER_CITY)
        
        # 2. Call OpenWeatherMap One Call API (if subscribed to 3.0) or free forecast API
        # Try using the standard One Call API to get hourly data
        url = OPENWEATHER_ONECALL_URL
        params = {
            "lat": lat,
            "lon": lon,
            "exclude": "current,minutely,daily,alerts",
            "appid": OPENWEATHER_API_KEY,
            "units": "metric" # Return temperature in Celsius
        }
        
        req = requests.get(url, params=params, timeout=10)
        # Fallback: if OneCall 3.0 is not subscribed (returns 401), fall back to 2.5/forecast API
        if req.status_code == 401:
            url = OPENWEATHER_FORECAST_URL
            params = {
                 "lat": lat,
                 "lon": lon,
                 "appid": OPENWEATHER_API_KEY,
                 "units": "metric"
            }
            req = requests.get(url, params=params, timeout=10)
            
        req.raise_for_status()
        data = req.json()

        # 3. Organize the list of hourly data to write into the database
        forecast_list = []

        if "hourly" in data:
            for item in data["hourly"]:
                weather_info = item["weather"][0] if "weather" in item and item["weather"] else {}
                forecast_list.append({
                    "forecast_time": from_unix_s_utc(item["dt"]),
                    "temperature": item.get("temp", 0),
                    "weather_code": weather_info.get("id", 800),
                    "description": weather_info.get("description", ""),
                    "icon": weather_info.get("icon", ""),
                    "feels_like": item.get("feels_like"),
                    "pressure": item.get("pressure"),
                    "humidity": item.get("humidity"),
                    "uvi": item.get("uvi"),
                    "clouds": item.get("clouds"),
                    "visibility": item.get("visibility"),
                    "wind_speed": item.get("wind_speed"),
                    "wind_deg": item.get("wind_deg"),
                    "pop": item.get("pop"),
                })
        # Process 2.5/forecast 3-hour list (usually 5 days, 40 records)
        elif "list" in data:
            for item in data["list"]:
                 weather_info = item["weather"][0] if "weather" in item and item["weather"] else {}
                 forecast_list.append({
                    "forecast_time": from_unix_s_utc(item["dt"]),
                    "temperature": item["main"].get("temp", 0),
                    "weather_code": weather_info.get("id", 800),
                    "description": weather_info.get("description", ""),
                    "icon": weather_info.get("icon", ""),
                    "feels_like": item["main"].get("feels_like"),
                    "pressure": item["main"].get("pressure"),
                    "humidity": item["main"].get("humidity"),
                    "uvi": None, # Forecast API rarely provides UV index
                    "clouds": item.get("clouds", {}).get("all"),
                    "visibility": item.get("visibility"),
                    "wind_speed": item.get("wind", {}).get("speed"),
                    "wind_deg": item.get("wind", {}).get("deg"),
                    "pop": item.get("pop"),
                })
        
        if not forecast_list:
            print(f"[{format_log_ts()}] Weather API returned no hourly data.")
            return

        # 4. Upsert logic + delete old data
        session = SessionLocal()
        try:
            # Clean up expired forecasts (earlier than current UTC hour)
            # The current hour on the dot
            current_hour = floor_hour_utc_naive()
            session.execute(delete(WeatherForecast).where(WeatherForecast.forecast_time < current_hour))
            
            inserted = 0
            updated = 0
            
            for item in forecast_list:
                # Only keep data for the next 48 hours max.
                if (item["forecast_time"] - current_hour).total_seconds() > 48 * 3600:
                    continue
                    
                existing = session.query(WeatherForecast).filter_by(forecast_time=item["forecast_time"]).first()
                if existing:
                    # Update
                    existing.temperature = item["temperature"]
                    existing.weather_code = item["weather_code"]
                    existing.description = item["description"]
                    existing.icon = item["icon"]
                    existing.feels_like = item["feels_like"]
                    existing.pressure = item["pressure"]
                    existing.humidity = item["humidity"]
                    existing.uvi = item["uvi"]
                    existing.clouds = item["clouds"]
                    existing.visibility = item["visibility"]
                    existing.wind_speed = item["wind_speed"]
                    existing.wind_deg = item["wind_deg"]
                    existing.pop = item["pop"]
                    existing.fetched_at = utc_now_naive()
                    updated += 1
                else:
                    # Insert
                    new_forecast = WeatherForecast(
                        forecast_time=item["forecast_time"],
                        temperature=item["temperature"],
                        weather_code=item["weather_code"],
                        description=item["description"],
                        icon=item["icon"],
                        feels_like=item["feels_like"],
                        pressure=item["pressure"],
                        humidity=item["humidity"],
                        uvi=item["uvi"],
                        clouds=item["clouds"],
                        visibility=item["visibility"],
                        wind_speed=item["wind_speed"],
                        wind_deg=item["wind_deg"],
                        pop=item["pop"],
                        fetched_at=utc_now_naive()
                    )
                    session.add(new_forecast)
                    inserted += 1
            
            session.commit()
            
            finished_at = utc_now()
            duration_sec = (finished_at - started_at).total_seconds()
            print(
                f"[{format_log_ts(finished_at)}] Weather scrape done | "
                f"Insert: {inserted}, Update: {updated} | "
                f"Elapsed: {duration_sec:.2f}s"
            )

        except Exception as e:
            session.rollback()
            raise WeatherScraperError(f"Database operation failed: {e}")
        finally:
            session.close()

    except Exception as e:
        print(f"[{format_log_ts()}] Weather scrape error: {e}")

if __name__ == "__main__":
    fetch_weather_and_store()
