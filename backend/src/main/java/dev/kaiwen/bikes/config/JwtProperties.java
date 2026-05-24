package dev.kaiwen.bikes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String accessSecret,
        String refreshSecret,
        int accessExpiresSeconds,
        int refreshExpiresSeconds) {}
