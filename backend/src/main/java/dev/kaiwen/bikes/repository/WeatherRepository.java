package dev.kaiwen.bikes.repository;

import dev.kaiwen.bikes.model.WeatherForecast;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeatherRepository extends JpaRepository<WeatherForecast, Integer> {

    List<WeatherForecast> findTop6ByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(
            LocalDateTime forecastTime);

    List<WeatherForecast> findByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(
            LocalDateTime forecastTime);
}
