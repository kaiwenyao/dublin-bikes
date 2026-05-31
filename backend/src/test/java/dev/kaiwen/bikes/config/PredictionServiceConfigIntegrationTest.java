package dev.kaiwen.bikes.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

@SpringBootTest
class PredictionServiceConfigIntegrationTest {

    @Autowired
    private RestClient predictionServiceRestClient;

    @Autowired
    private PredictionServiceProperties predictionServiceProperties;

    @Test
    void predictionServiceRestClient_isConfiguredFromProperties() {
        assertThat(predictionServiceRestClient).isNotNull();
        assertThat(predictionServiceProperties.baseUrl()).isEqualTo("http://localhost:8001");
        assertThat(predictionServiceProperties.connectTimeoutMs()).isEqualTo(1000);
        assertThat(predictionServiceProperties.readTimeoutMs()).isEqualTo(5000);
    }
}
