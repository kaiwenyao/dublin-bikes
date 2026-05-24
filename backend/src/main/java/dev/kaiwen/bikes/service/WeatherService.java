package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.WeatherDataVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.mapper.WeatherMapper;
import dev.kaiwen.bikes.model.WeatherForecast;
import dev.kaiwen.bikes.repository.WeatherRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WeatherService {

    private final WeatherRepository weatherRepository;
    private final WeatherMapper weatherMapper;

    public WeatherDataVO getWeather() {
        LocalDateTime nowHour =
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        List<WeatherForecast> rows =
                weatherRepository.findTop6ByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(nowHour);
        if (rows.isEmpty()) {
            throw new BusinessException(
                    ApiCodes.WEATHER_ERROR, "No weather data available in database", 404);
        }

        WeatherDataVO data = new WeatherDataVO();
        data.setCurrent(weatherMapper.toCurrentVO(rows.getFirst()));
        if (rows.size() > 1) {
            data.setHourly(weatherMapper.toHourlyVOList(rows.subList(1, rows.size())));
        }
        return data;
    }
}
