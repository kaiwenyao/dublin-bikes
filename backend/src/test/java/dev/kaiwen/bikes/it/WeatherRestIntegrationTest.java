package dev.kaiwen.bikes.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaiwen.bikes.model.WeatherForecast;
import dev.kaiwen.bikes.repository.WeatherRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 天气 REST API 集成测试：真实 PostgreSQL + Flyway + JPA/Hibernate。
 *
 * <p>覆盖 {@code GET /api/weather}（公开接口）：
 * <ul>
 *   <li>数据库有数据时返回封装的 One Call 兼容结构</li>
 *   <li>数据库无未来时段数据时返回 404（业务码 WEATHER_ERROR）</li>
 * </ul>
 */
class WeatherRestIntegrationTest extends IntegrationTestBase {

    @Autowired private WeatherRepository weatherRepository;

    @BeforeEach
    void cleanDatabase() {
        weatherRepository.deleteAllInBatch();
    }

    @Test
    void getWeather_returnsCurrentAndHourlyFromDatabase() {
        // Anchor forecasts far in the future (one year ahead) so they are ALWAYS
        // >= the service's `nowHour`, regardless of which UTC hour the GET runs
        // in. The service query is `forecast_time >= nowHour ORDER BY forecast_time
        // ASC`, so `current` is the smallest forecast_time and `hourly` is the
        // rest — both fully deterministic this way, no hour-boundary flake and no
        // need to widen the assertions. (Past/future has no special meaning to the
        // endpoint; it only filters by `>= nowHour`.)
        LocalDateTime baseHour =
                LocalDateTime.now(ZoneOffset.UTC).plusYears(1).truncatedTo(ChronoUnit.HOURS);

        persistForecast(baseHour, 15.5f);
        persistForecast(baseHour.plusHours(1), 16.0f);
        persistForecast(baseHour.plusHours(2), 16.5f);

        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/weather", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(0);

        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data).isNotNull();
        Map<?, ?> current = (Map<?, ?>) data.get("current");
        assertThat(current).isNotNull();
        // Smallest forecast_time in the result set (15.5 at baseHour).
        assertThat(current.get("temp")).isEqualTo(15.5);

        java.util.List<?> hourly = (java.util.List<?>) data.get("hourly");
        assertThat(hourly).isNotNull();
        // Remaining two rows (16.0, 16.5); tightened back from >= 1.
        assertThat(hourly).hasSize(2);
    }

    @Test
    void getWeather_whenNoData_returns404() {
        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/weather", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        // WEATHER_ERROR = 50001
        assertThat(body.get("code")).isEqualTo(50001);
    }

    private void persistForecast(LocalDateTime forecastTime, float temperature) {
        WeatherForecast forecast = new WeatherForecast();
        forecast.setForecastTime(forecastTime);
        forecast.setTemperature(temperature);
        forecast.setWeatherCode(803);
        forecast.setDescription("broken clouds");
        forecast.setIcon("04d");
        forecast.setFeelsLike(14.0f);
        forecast.setPressure(1019);
        forecast.setHumidity(78);
        forecast.setUvi(0.3f);
        forecast.setClouds(90);
        forecast.setVisibility(10000);
        forecast.setWindSpeed(5.4f);
        forecast.setWindDeg(240);
        forecast.setPop(0.0f);
        weatherRepository.saveAndFlush(forecast);
    }
}
