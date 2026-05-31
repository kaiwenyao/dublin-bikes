package dev.kaiwen.bikes.client;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class PredictionServiceClient {

    private final RestClient predictionServiceRestClient;

    public record PredictResponse(List<Integer> predictions) {}

    public PredictResponse predict(List<Map<String, Object>> rows) {
        return predictionServiceRestClient
                .post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rows", rows))
                .retrieve()
                .body(PredictResponse.class);
    }

    public void warmup() {
        predictionServiceRestClient.get().uri("/health").retrieve().toBodilessEntity();
    }
}
