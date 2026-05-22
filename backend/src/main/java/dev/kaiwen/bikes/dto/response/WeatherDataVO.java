package dev.kaiwen.bikes.dto.response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public class WeatherDataVO {

    private WeatherCurrentVO current;
    private List<WeatherHourlyVO> hourly;
    private final Map<String, Object> additional = new HashMap<>();

    public WeatherCurrentVO getCurrent() {
        return current;
    }

    public void setCurrent(WeatherCurrentVO current) {
        this.current = current;
    }

    public List<WeatherHourlyVO> getHourly() {
        return hourly;
    }

    public void setHourly(List<WeatherHourlyVO> hourly) {
        this.hourly = hourly;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditional() {
        return additional;
    }

    @JsonAnySetter
    public void setAdditional(String key, Object value) {
        additional.put(key, value);
    }
}
