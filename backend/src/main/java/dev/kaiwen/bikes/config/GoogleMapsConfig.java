package dev.kaiwen.bikes.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GoogleMapsProperties.class)
public class GoogleMapsConfig {

    @Bean
    RestClient googleMapsRestClient(GoogleMapsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return RestClient.builder()
                .baseUrl("https://maps.googleapis.com")
                .requestFactory(factory)
                .build();
    }
}
