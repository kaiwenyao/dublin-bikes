package dev.kaiwen.bikes.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, VerificationProperties.class, MailProperties.class})
public class AppPropertiesConfig {}
