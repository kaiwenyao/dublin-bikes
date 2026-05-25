package dev.kaiwen.bikes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.google-maps")
public record GoogleMapsProperties(
        String apiKey, int connectTimeoutMs, int readTimeoutMs, int maxRetries) {}
