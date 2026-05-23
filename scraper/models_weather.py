from datetime import datetime

from sqlalchemy import DateTime, Float, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from database import Base
from time_utils import utc_now_naive


class WeatherForecast(Base):
    __tablename__ = "weather_forecast"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    # Forecast valid time as naive UTC (e.g. 2023-10-01 14:00:00)
    # We will use this to query the next 5 hours and also Upsert existing forecasts.
    forecast_time: Mapped[datetime] = mapped_column(DateTime, unique=True, index=True)
    
    # Temperature in Celsius
    temperature: Mapped[float] = mapped_column(Float)
    
    # Weather condition code (e.g., 800 for Clear, 500 for Rain)
    weather_code: Mapped[int] = mapped_column(Integer)
    
    # Weather description (e.g. "scattered clouds")
    description: Mapped[str] = mapped_column(String(100))
    
    # Optional: ICON code (e.g. "01d", "10n")
    icon: Mapped[str] = mapped_column(String(20), nullable=True)

    # Extra OpenWeather metrics
    feels_like: Mapped[float] = mapped_column(Float, nullable=True)
    pressure: Mapped[int] = mapped_column(Integer, nullable=True)
    humidity: Mapped[int] = mapped_column(Integer, nullable=True)
    uvi: Mapped[float] = mapped_column(Float, nullable=True)
    clouds: Mapped[int] = mapped_column(Integer, nullable=True)
    visibility: Mapped[int] = mapped_column(Integer, nullable=True)
    wind_speed: Mapped[float] = mapped_column(Float, nullable=True)
    wind_deg: Mapped[int] = mapped_column(Integer, nullable=True)
    pop: Mapped[float] = mapped_column(Float, nullable=True, default=0.0)

    fetched_at: Mapped[datetime] = mapped_column(DateTime, default=utc_now_naive)
