package dev.kaiwen.bikes.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record WeatherCurrentVO(
        Long dt,
        Float temp,
        Float feelsLike,
        Integer pressure,
        Integer humidity,
        Float uvi,
        Integer clouds,
        Integer visibility,
        Float windSpeed,
        Integer windDeg,
        List<WeatherConditionVO> weather) {}
