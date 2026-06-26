package dev.kaiwen.bikes.service;

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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.prediction-service", name = "base-url")
public class PredictionService {

    private final StationRepository stationRepository;
    private final WeatherRepository weatherRepository;
    private final PredictionServiceClient client;

    public List<PredictionPointVO> predict(int stationNumber) {
        Station station =
                stationRepository
                        .findById(stationNumber)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ApiCodes.STATION_NOT_FOUND,
                                                "Station " + stationNumber + " not found",
                                                404));

        LocalDateTime currentHour =
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        List<WeatherForecast> forecasts =
                weatherRepository.findByForecastTimeGreaterThanEqualOrderByForecastTimeAsc(
                        currentHour);

        if (forecasts.isEmpty()) {
            throw new BusinessException(
                    ApiCodes.WEATHER_ERROR,
                    "No weather forecast data available to make predictions",
                    404);
        }

        List<Map<String, Object>> rows = new ArrayList<>(forecasts.size());
        for (WeatherForecast f : forecasts) {
            rows.add(buildFeatureRow(station, f));
        }

        PredictResponse response = client.predict(rows);
        List<Integer> predictions = response != null ? response.predictions() : null;
        if (predictions == null || predictions.size() != forecasts.size()) {
            throw new BusinessException(
                    ApiCodes.GENERIC_ERROR,
                    "Prediction service returned an unexpected response",
                    502);
        }

        int capacity = station.getBikeStands();
        List<PredictionPointVO> out = new ArrayList<>(forecasts.size());
        for (int i = 0; i < forecasts.size(); i++) {
            int clamped = Math.max(0, Math.min(predictions.get(i), capacity));
            String iso =
                    forecasts.get(i)
                            .getForecastTime()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            out.add(new PredictionPointVO(iso, clamped));
        }
        return out;
    }

    private static Map<String, Object> buildFeatureRow(Station station, WeatherForecast f) {
        LocalDateTime t = f.getForecastTime();
        // Python datetime.weekday(): Mon=0..Sun=6. Java DayOfWeek: Mon=1..Sun=7. Subtract 1.
        int dayOfWeek = t.getDayOfWeek().getValue() - 1;
        int isWeekend = dayOfWeek >= 5 ? 1 : 0;

        Map<String, Object> row = new HashMap<>(11);
        row.put("station_id", station.getNumber());
        row.put("capacity", station.getBikeStands());
        row.put("lat", station.getLatitude());
        row.put("lon", station.getLongitude());
        row.put("hour", t.getHour());
        row.put("day", t.getDayOfMonth());
        row.put("day_of_week", dayOfWeek);
        row.put("is_weekend", isWeekend);
        row.put("avg_temperature", f.getTemperature());
        row.put("avg_humidity", f.getHumidity());
        row.put("avg_pressure", f.getPressure());
        return row;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warmup() {
        try {
            client.warmup();
            log.info("prediction service warmup ok");
        } catch (Exception e) {
            log.warn("prediction service warmup failed: {}", e.getMessage());
        }
    }
}
