package dev.kaiwen.bikes.mapper;

import dev.kaiwen.bikes.dto.response.WeatherConditionVO;
import dev.kaiwen.bikes.dto.response.WeatherCurrentVO;
import dev.kaiwen.bikes.dto.response.WeatherHourlyVO;
import dev.kaiwen.bikes.model.WeatherForecast;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WeatherMapper {

    @Mapping(target = "dt", expression = "java(toEpochSecond(forecast.getForecastTime()))")
    @Mapping(target = "temp", source = "temperature")
    @Mapping(target = "feelsLike", source = "feelsLike")
    @Mapping(target = "windSpeed", source = "windSpeed")
    @Mapping(target = "windDeg", source = "windDeg")
    @Mapping(target = "weather", expression = "java(toWeatherConditions(forecast))")
    WeatherCurrentVO toCurrentVO(WeatherForecast forecast);

    @Mapping(target = "dt", expression = "java(toEpochSecond(forecast.getForecastTime()))")
    @Mapping(target = "temp", source = "temperature")
    WeatherHourlyVO toHourlyVO(WeatherForecast forecast);

    List<WeatherHourlyVO> toHourlyVOList(List<WeatherForecast> forecasts);

    default Long toEpochSecond(LocalDateTime forecastTime) {
        if (forecastTime == null) {
            return null;
        }
        return forecastTime.toEpochSecond(ZoneOffset.UTC);
    }

    default List<WeatherConditionVO> toWeatherConditions(WeatherForecast forecast) {
        return List.of(new WeatherConditionVO(
                forecast.getWeatherCode(), forecast.getDescription(), forecast.getIcon()));
    }
}
