package dev.kaiwen.bikes.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaiwen.bikes.model.Station;
import dev.kaiwen.bikes.model.WeatherForecast;
import dev.kaiwen.bikes.repository.StationRepository;
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
 * 站点可用量预测 REST API 集成测试：真实 PostgreSQL + Flyway。
 *
 * <p>覆盖 {@code GET /api/stations/{number}/prediction}：
 * <ul>
 *   <li>站点与天气预测数据齐全、但 prediction-service 不可达（it profile 假地址）时，
 *       服务层完成站点查询、特征行构造并发起上游调用，最终返回 500</li>
 *   <li>站点不存在返回 404（业务码 STATION_NOT_FOUND）</li>
 *   <li>无未来天气预测数据返回 404（业务码 WEATHER_ERROR）</li>
 * </ul>
 */
class StationPredictionRestIntegrationTest extends IntegrationTestBase {

    @Autowired private StationRepository stationRepository;
    @Autowired private WeatherRepository weatherRepository;

    @BeforeEach
    void cleanDatabase() {
        weatherRepository.deleteAllInBatch();
        stationRepository.deleteAllInBatch();
    }

    @Test
    void prediction_upstreamUnreachable_returns500() {
        persistStation(42);
        LocalDateTime baseHour =
                LocalDateTime.now(ZoneOffset.UTC).plusYears(1).truncatedTo(ChronoUnit.HOURS);
        persistForecast(baseHour);
        persistForecast(baseHour.plusHours(1));

        ResponseEntity<Map> resp =
                restTemplate.getForEntity("/api/stations/42/prediction", Map.class);

        // prediction-service 在 it 环境指向假地址，连接被拒 → 通用错误兜底
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        // GENERIC_ERROR = 50000
        assertThat(resp.getBody().get("code")).isEqualTo(50000);
    }

    @Test
    void prediction_unknownStation_returns404() {
        ResponseEntity<Map> resp =
                restTemplate.getForEntity("/api/stations/999/prediction", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        // STATION_NOT_FOUND = 1
        assertThat(resp.getBody().get("code")).isEqualTo(1);
    }

    @Test
    void prediction_noForecastData_returns404() {
        persistStation(42);

        ResponseEntity<Map> resp =
                restTemplate.getForEntity("/api/stations/42/prediction", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        // WEATHER_ERROR = 50001
        assertThat(resp.getBody().get("code")).isEqualTo(50001);
    }

    private void persistStation(int number) {
        Station station = new Station();
        station.setNumber(number);
        station.setContractName("dublin");
        station.setName("Station " + number);
        station.setAddress("Address " + number);
        station.setLatitude(53.34f);
        station.setLongitude(-6.26f);
        station.setBanking(true);
        station.setBonus(false);
        station.setBikeStands(30);
        stationRepository.saveAndFlush(station);
    }

    private void persistForecast(LocalDateTime forecastTime) {
        WeatherForecast forecast = new WeatherForecast();
        forecast.setForecastTime(forecastTime);
        forecast.setTemperature(15.5f);
        forecast.setWeatherCode(800);
        forecast.setHumidity(80);
        forecast.setPressure(1013);
        weatherRepository.saveAndFlush(forecast);
    }
}
