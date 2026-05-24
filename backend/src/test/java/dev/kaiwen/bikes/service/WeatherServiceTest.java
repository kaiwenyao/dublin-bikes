package dev.kaiwen.bikes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.WeatherConditionVO;
import dev.kaiwen.bikes.dto.response.WeatherCurrentVO;
import dev.kaiwen.bikes.dto.response.WeatherDataVO;
import dev.kaiwen.bikes.dto.response.WeatherHourlyVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.mapper.WeatherMapper;
import dev.kaiwen.bikes.model.WeatherForecast;
import dev.kaiwen.bikes.repository.WeatherRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {

    @Mock
    private WeatherRepository weatherRepository;

    @Mock
    private WeatherMapper weatherMapper;

    @InjectMocks
    private WeatherService weatherService;

    @Test
    void getWeather_whenNoRows_throwsWeatherError() {
        when(weatherRepository.findTop6ByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> weatherService.getWeather())
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ApiCodes.WEATHER_ERROR);
                    assertThat(be.getStatus()).isEqualTo(404);
                    assertThat(be.getMessage()).isEqualTo("No weather data available in database");
                });
    }

    @Test
    void getWeather_mapsCurrentAndHourly() {
        WeatherForecast current = forecastAt("2025-01-20T10:00:00");
        WeatherForecast hourly = forecastAt("2025-01-20T11:00:00");
        WeatherCurrentVO currentVo = new WeatherCurrentVO(
                1737367200L,
                4.5f,
                1.2f,
                1019,
                78,
                0.3f,
                90,
                10000,
                5.4f,
                240,
                List.of(new WeatherConditionVO(803, "broken clouds", "04d")));
        WeatherHourlyVO hourlyVo = new WeatherHourlyVO(1737370800L, 4.7f);

        when(weatherRepository.findTop6ByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(any()))
                .thenReturn(List.of(current, hourly));
        when(weatherMapper.toCurrentVO(current)).thenReturn(currentVo);
        when(weatherMapper.toHourlyVOList(List.of(hourly))).thenReturn(List.of(hourlyVo));

        WeatherDataVO data = weatherService.getWeather();

        assertThat(data.getCurrent()).isEqualTo(currentVo);
        assertThat(data.getHourly()).containsExactly(hourlyVo);
    }

    private static WeatherForecast forecastAt(String iso) {
        WeatherForecast forecast = new WeatherForecast();
        forecast.setForecastTime(LocalDateTime.parse(iso));
        forecast.setTemperature(4.5f);
        forecast.setWeatherCode(803);
        forecast.setDescription("broken clouds");
        forecast.setIcon("04d");
        return forecast;
    }
}
