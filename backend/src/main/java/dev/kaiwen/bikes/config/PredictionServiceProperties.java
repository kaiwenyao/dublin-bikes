package dev.kaiwen.bikes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.prediction-service")
public record PredictionServiceProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs) {}
