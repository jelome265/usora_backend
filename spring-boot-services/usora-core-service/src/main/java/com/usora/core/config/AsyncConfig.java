package com.usora.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    @Bean
    public AsyncTaskExecutor virtualThreadTaskExecutor() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        return new TaskExecutorAdapter(executor);
    }

    @Bean
    public SimpleAsyncTaskScheduler virtualThreadTaskScheduler() {
        var scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("orchestrator-sched-");
        return scheduler;
    }
}
