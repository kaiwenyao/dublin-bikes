package dev.kaiwen.bikes.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PredictionServiceProperties.class)
public class PredictionServiceConfig {

    /**
     * Shared JDK HTTP client: one keep-alive connection pool reused by every per-station
     * prediction call instead of a fresh {@code HttpURLConnection} per request.
     */
    @Bean
    HttpClient predictionServiceHttpClient(PredictionServiceProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
    }

    @Bean
    RestClient predictionServiceRestClient(PredictionServiceProperties properties) {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(predictionServiceHttpClient(properties));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
