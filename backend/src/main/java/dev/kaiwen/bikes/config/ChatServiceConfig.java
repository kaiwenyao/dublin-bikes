package dev.kaiwen.bikes.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ChatServiceProperties.class)
public class ChatServiceConfig {

    @Bean
    RestClient chatServiceRestClient(ChatServiceProperties properties) {
        return buildRestClient(properties, properties.readTimeoutMs());
    }

    @Bean
    RestClient chatServiceTitleRestClient(ChatServiceProperties properties) {
        return buildRestClient(properties, properties.titleTimeoutMs());
    }

    private static RestClient buildRestClient(ChatServiceProperties properties, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
