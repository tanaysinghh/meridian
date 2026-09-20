package com.meridian.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The executor behind post-ingest risk scoring.
 *
 * <p>Scoring runs off the request thread so a GitHub delivery is acknowledged before the ML service
 * is called — GitHub's delivery timeout is short, and a slow model would otherwise turn into
 * redeliveries and duplicate work.
 *
 * <p>The queue is bounded and the rejection policy is {@code CallerRuns}. Under a burst larger than
 * the pool and queue can absorb, the webhook thread does the scoring itself: slower, but it means a
 * spike is throttled at the source rather than silently dropping scores or growing an unbounded
 * queue until the process runs out of memory.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean("webhookScoringExecutor")
    public Executor webhookScoringExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("scoring-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight scoring finish on shutdown rather than losing it.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // Nothing awaits these tasks, so an escaped exception would otherwise vanish.
        return (throwable, method, params) -> {
            log.error("async_task_failed method={} reason={}", method.getName(), throwable.getMessage(), throwable);
            new SimpleAsyncUncaughtExceptionHandler().handleUncaughtException(throwable, method, params);
        };
    }
}
