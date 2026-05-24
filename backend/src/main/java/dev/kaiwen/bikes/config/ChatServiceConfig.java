package dev.kaiwen.bikes.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ChatServiceProperties.class)
public class ChatServiceConfig {

  @Bean
  RestClient chatServiceRestClient(ChatServiceProperties properties) {
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .build();
  }
}
