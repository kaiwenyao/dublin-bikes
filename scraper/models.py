from __future__ import annotations

from datetime import datetime

from sqlalchemy import BigInteger, Boolean, DateTime, Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from database import Base
from time_utils import utc_now_naive


class Station(Base):
    __tablename__ = "station"

    number: Mapped[int] = mapped_column(Integer, primary_key=True)
    contract_name: Mapped[str] = mapped_column(String(50))
    name: Mapped[str] = mapped_column(String(100))
    address: Mapped[str] = mapped_column(String(200))
    latitude: Mapped[float] = mapped_column(Float)
    longitude: Mapped[float] = mapped_column(Float)
    # Whether on-site card payment is supported (JCDecaux API: banking). 0=no, 1=yes
    banking: Mapped[bool] = mapped_column(Boolean, comment="Whether on-site card payment is supported; 0=no, 1=yes")
    # Whether this is a bonus station (e.g., returning a bike grants extra time). 0=no, 1=yes
    bonus: Mapped[bool] = mapped_column(Boolean, comment="Whether this is a bonus station (returning a bike grants extra time); 0=no, 1=yes")
    bike_stands: Mapped[int] = mapped_column(Integer)

    availabilities: Mapped[list[Availability]] = relationship(
        "Availability",
        back_populates="station",
        cascade="all, delete",
    )


class Availability(Base):
    __tablename__ = "availability"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    number: Mapped[int] = mapped_column(ForeignKey("station.number"), nullable=False)
    available_bikes: Mapped[int] = mapped_column(Integer)
    available_bike_stands: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(20))
    last_update: Mapped[int] = mapped_column(BigInteger)
    timestamp: Mapped[datetime] = mapped_column(DateTime, default=utc_now_naive)
    requested_at: Mapped[datetime] = mapped_column(
        DateTime,
        default=utc_now_naive,
        comment="UTC scrape time (naive DATETIME, application writes UTC)",
    )

    station: Mapped[Station] = relationship("Station", back_populates="availabilities")
