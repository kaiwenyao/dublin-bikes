package dev.kaiwen.bikes.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GoogleMapsProperties.class)
public class GoogleMapsConfig {

    /**
     * Shared JDK HTTP client: one instance owns one connection pool, and the JDK client keeps
     * connections alive between requests. A journey plan issues up to six Distance Matrix /
     * Geocoding calls in a row, so reusing the TCP + TLS handshake instead of paying it per call
     * removes most of the per-call overhead. (The previous {@code SimpleClientHttpRequestFactory}
     * wraps {@code HttpURLConnection}, which has no pool.)
     */
    @Bean
    HttpClient googleMapsHttpClient(GoogleMapsProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    RestClient googleMapsRestClient(GoogleMapsProperties properties) {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(googleMapsHttpClient(properties));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return RestClient.builder()
                .baseUrl("https://maps.googleapis.com")
                .requestFactory(factory)
                .build();
    }
}
