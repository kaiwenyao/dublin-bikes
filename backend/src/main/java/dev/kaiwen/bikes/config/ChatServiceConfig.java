package dev.kaiwen.bikes.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ChatServiceProperties.class)
public class ChatServiceConfig {

    /**
     * Shared JDK HTTP client for every call to chat-service: the blocking title/history requests
     * through {@link RestClient} and the SSE relay in {@code ChatService}.
     *
     * <p>HTTP/1.1 is forced: Uvicorn/FastAPI over HTTP/2 drops POST bodies from
     * {@code java.net.http.HttpClient} (422), and the SSE upstream expects HTTP/1.1-style
     * streaming. Before this bean, {@code ChatService} built a brand-new client (and its executor)
     * for every chat message, so connections were never reused and abandoned clients accumulated.
     */
    @Bean
    HttpClient chatServiceHttpClient(ChatServiceProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
    }

    @Bean
    RestClient chatServiceRestClient(
            ChatServiceProperties properties,
            @Qualifier(HttpClientConfig.HTTP_BODY_EXECUTOR) Executor httpBodyExecutor) {
        return buildRestClient(properties, properties.readTimeoutMs(), httpBodyExecutor);
    }

    @Bean
    RestClient chatServiceTitleRestClient(
            ChatServiceProperties properties,
            @Qualifier(HttpClientConfig.HTTP_BODY_EXECUTOR) Executor httpBodyExecutor) {
        return buildRestClient(properties, properties.titleTimeoutMs(), httpBodyExecutor);
    }

    private RestClient buildRestClient(
            ChatServiceProperties properties, int readTimeoutMs, Executor httpBodyExecutor) {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        chatServiceHttpClient(properties), httpBodyExecutor);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
