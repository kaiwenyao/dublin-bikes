package dev.kaiwen.bikes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import dev.kaiwen.bikes.client.PredictionServiceClient;
import dev.kaiwen.bikes.client.PredictionServiceClient.PredictResponse;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.PredictionPointVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.model.Station;
import dev.kaiwen.bikes.model.WeatherForecast;
import dev.kaiwen.bikes.repository.StationRepository;
import dev.kaiwen.bikes.repository.WeatherRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PredictionServiceTest {

    @Mock private StationRepository stationRepository;
    @Mock private WeatherRepository weatherRepository;
    @Mock private PredictionServiceClient client;

    @InjectMocks private PredictionService predictionService;

    @Test
    void predict_happyPath_clampsPredictionsToCapacityRange() {
        Station station = station(42, 30);
        when(stationRepository.findById(42)).thenReturn(Optional.of(station));
        LocalDateTime t0 =
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).plusHours(1);
        // 一个落在周六（is_weekend=1），一个落在周一（is_weekend=0），覆盖两个分支
        LocalDateTime saturday = t0.plusDays((6 - t0.getDayOfWeek().getValue() + 7) % 7);
        List<WeatherForecast> forecasts =
                List.of(forecast(t0), forecast(saturday));
        when(weatherRepository.findByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(any()))
                .thenReturn(forecasts);
        // 第一个超过容量上限，第二个为负数，验证钳制逻辑
        when(client.predict(anyList())).thenReturn(new PredictResponse(List.of(45, -3)));

        List<PredictionPointVO> result = predictionService.predict(42);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).predictedAvailableBikes()).isEqualTo(30);
        assertThat(result.get(1).predictedAvailableBikes()).isEqualTo(0);
        assertThat(result.get(0).forecastTime()).isEqualTo(t0.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(client).predict(captor.capture());
        Map<String, Object> row = captor.getValue().get(0);
        assertThat(row.get("station_id")).isEqualTo(42);
        assertThat(row.get("capacity")).isEqualTo(30);
        assertThat(row.get("lat")).isEqualTo(53.34f);
        assertThat(row.get("lon")).isEqualTo(-6.26f);
        assertThat(row.get("hour")).isEqualTo(t0.getHour());
        assertThat(row.get("day")).isEqualTo(t0.getDayOfMonth());
        assertThat(row).containsKeys("day_of_week", "is_weekend", "avg_temperature", "avg_humidity", "avg_pressure");
    }

    @Test
    void predict_unknownStation_throws404() {
        when(stationRepository.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> predictionService.predict(999))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.STATION_NOT_FOUND);
                            assertThat(be.getStatus()).isEqualTo(404);
                        });
    }

    @Test
    void predict_noForecastData_throws404() {
        when(stationRepository.findById(42)).thenReturn(Optional.of(station(42, 30)));
        when(weatherRepository.findByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> predictionService.predict(42))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.WEATHER_ERROR);
                            assertThat(be.getStatus()).isEqualTo(404);
                        });
    }

    @Test
    void predict_nullResponse_throws502() {
        when(stationRepository.findById(42)).thenReturn(Optional.of(station(42, 30)));
        when(weatherRepository.findByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(any()))
                .thenReturn(List.of(forecast(LocalDateTime.now(ZoneOffset.UTC).plusHours(1))));
        when(client.predict(anyList())).thenReturn(null);

        assertThatThrownBy(() -> predictionService.predict(42))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.GENERIC_ERROR);
                            assertThat(be.getStatus()).isEqualTo(502);
                        });
    }

    @Test
    void predict_predictionCountMismatch_throws502() {
        when(stationRepository.findById(42)).thenReturn(Optional.of(station(42, 30)));
        when(weatherRepository.findByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(any()))
                .thenReturn(
                        List.of(
                                forecast(LocalDateTime.now(ZoneOffset.UTC).plusHours(1)),
                                forecast(LocalDateTime.now(ZoneOffset.UTC).plusHours(2))));
        when(client.predict(anyList())).thenReturn(new PredictResponse(List.of(10)));

        assertThatThrownBy(() -> predictionService.predict(42))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(502));
    }

    @Test
    void warmup_success_isSilent() {
        assertThatCode(() -> predictionService.warmup()).doesNotThrowAnyException();
        Mockito.verify(client).warmup();
    }

    @Test
    void warmup_upstreamDown_isSwallowed() {
        doThrow(new RuntimeException("connection refused")).when(client).warmup();

        assertThatCode(() -> predictionService.warmup()).doesNotThrowAnyException();
    }

    private static Station station(int number, int bikeStands) {
        Station station = new Station();
        station.setNumber(number);
        station.setContractName("dublin");
        station.setName("Station " + number);
        station.setAddress("Address " + number);
        station.setLatitude(53.34f);
        station.setLongitude(-6.26f);
        station.setBanking(true);
        station.setBonus(false);
        station.setBikeStands(bikeStands);
        return station;
    }

    private static WeatherForecast forecast(LocalDateTime forecastTime) {
        WeatherForecast forecast = new WeatherForecast();
        forecast.setForecastTime(forecastTime);
        forecast.setTemperature(15.5f);
        forecast.setHumidity(80);
        forecast.setPressure(1013);
        return forecast;
    }
}
