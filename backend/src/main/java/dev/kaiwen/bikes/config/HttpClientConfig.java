package dev.kaiwen.bikes.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Shared executor for blocking HTTP request-body writes.
 *
 * <p>Spring's {@code JdkClientHttpRequestFactory} falls back to a
 * {@code SimpleAsyncTaskExecutor} when it is not given an executor: that creates one new platform
 * thread per request body, never reuses it and applies no concurrency bound. The bodies here are
 * small (prediction rows, chat title payloads, no bodies on the Google GETs), so a small bounded
 * pool with caller-runs backpressure keeps the thread count flat under load.
 */
@Configuration
public class HttpClientConfig {

    /** Bean name injected by the upstream {@code RestClient} configurations. */
    public static final String HTTP_BODY_EXECUTOR = "httpBodyExecutor";

    @Bean(name = HTTP_BODY_EXECUTOR)
    public Executor httpBodyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("http-body-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
