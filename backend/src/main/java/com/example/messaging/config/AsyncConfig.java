package com.example.messaging.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded executors. The dispatch executor is sized from {@code messaging.dispatch.concurrency} and rejects (caller
 * runs) instead of growing without bound, which gives natural backpressure to the consumer loop.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "dispatchExecutor")
    public ThreadPoolTaskExecutor dispatchExecutor(MessagingProperties props) {
        int n = Math.max(1, props.dispatch().concurrency());
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("dispatch-");
        ex.setCorePoolSize(n);
        ex.setMaxPoolSize(n);
        ex.setQueueCapacity(Math.max(1, props.dispatch().prefetch()));
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        return ex;
    }
}
