package dev.kaiwen.bikes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.verification")
public record VerificationProperties(int codeExpireSeconds, int resendCooldownSeconds) {}
