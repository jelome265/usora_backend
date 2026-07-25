package com.usora.compliance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "complianceTaskExecutor")
    public Executor complianceTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("compliance-async-");
        executor.setVirtualThreadsEnabled(true);
        executor.initialize();
        return executor;
    }

    @Bean(name = "reportGenerationExecutor")
    public Executor reportGenerationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("compliance-report-");
        executor.setVirtualThreadsEnabled(true);
        executor.initialize();
        return executor;
    }
}
