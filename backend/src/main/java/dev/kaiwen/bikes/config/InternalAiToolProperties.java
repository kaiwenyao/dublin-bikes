package dev.kaiwen.bikes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.internal-ai-tools")
public record InternalAiToolProperties(String serviceToken) {}
