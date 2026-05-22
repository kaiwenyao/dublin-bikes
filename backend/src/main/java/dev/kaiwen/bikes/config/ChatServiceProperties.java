package dev.kaiwen.bikes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat-service")
public record ChatServiceProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        int streamTimeoutMs,
        int titleTimeoutMs) {}
