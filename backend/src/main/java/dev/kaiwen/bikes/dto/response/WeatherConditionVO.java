package dev.kaiwen.bikes.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record WeatherConditionVO(Integer id, String description, String icon) {}
