package dev.kaiwen.bikes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "weather_forecast")
@Getter
@Setter
@NoArgsConstructor
public class WeatherForecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "forecast_time", nullable = false, unique = true)
    private LocalDateTime forecastTime;

    @Column(nullable = false)
    private Float temperature;

    @Column(name = "weather_code", nullable = false)
    private Integer weatherCode;

    @Column(length = 100)
    private String description;

    @Column(length = 20)
    private String icon;

    @Column(name = "feels_like")
    private Float feelsLike;

    private Integer pressure;

    private Integer humidity;

    private Float uvi;

    private Integer clouds;

    private Integer visibility;

    @Column(name = "wind_speed")
    private Float windSpeed;

    @Column(name = "wind_deg")
    private Integer windDeg;

    private Float pop;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;
}
