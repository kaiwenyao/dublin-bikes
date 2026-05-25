package dev.kaiwen.bikes.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

/**
 * Chat proxy (Spring → Python) is not implemented yet ({@code ChatController} pending).
 * This test locks in the RestClient wiring used by the future {@code ChatServiceClient}.
 */
@SpringBootTest
class ChatServiceConfigIntegrationTest {

    @Autowired
    private RestClient chatServiceRestClient;

    @Autowired
    private RestClient chatServiceTitleRestClient;

    @Autowired
    private ChatServiceProperties chatServiceProperties;

    @Test
    void chatServiceRestClient_isConfiguredFromProperties() {
        assertThat(chatServiceRestClient).isNotNull();
        assertThat(chatServiceTitleRestClient).isNotNull();
        assertThat(chatServiceProperties.baseUrl()).isEqualTo("http://localhost:8002");
        assertThat(chatServiceProperties.connectTimeoutMs()).isEqualTo(1000);
        assertThat(chatServiceProperties.readTimeoutMs()).isEqualTo(5000);
        assertThat(chatServiceProperties.titleTimeoutMs()).isEqualTo(3000);
    }
}
