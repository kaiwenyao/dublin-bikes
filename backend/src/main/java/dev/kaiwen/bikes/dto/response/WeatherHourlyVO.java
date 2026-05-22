package dev.kaiwen.bikes.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record WeatherHourlyVO(Long dt, Float temp) {}
