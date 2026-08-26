package dev.kaiwen.bikes.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.kaiwen.bikes.client.PredictionServiceClient.PredictResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** {@link PredictionServiceClient} 的 HTTP 层测试：/predict 与 /health 的请求与解析。 */
class PredictionServiceClientTest {

    private MockRestServiceServer server;
    private PredictionServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PredictionServiceClient(builder.build());
    }

    @Test
    void predict_postsRowsAndParsesPredictions() {
        server.expect(requestTo("http://localhost/predict"))
                .andRespond(
                        withSuccess("{\"predictions\":[7,12]}", MediaType.APPLICATION_JSON));

        PredictResponse response = client.predict(List.of(Map.of("station_id", 42)));

        assertThat(response).isNotNull();
        assertThat(response.predictions()).containsExactly(7, 12);
        server.verify();
    }

    @Test
    void warmup_callsHealthEndpoint() {
        server.expect(requestTo("http://localhost/health")).andRespond(withSuccess());

        assertThatCode(() -> client.warmup()).doesNotThrowAnyException();
        server.verify();
    }
}
