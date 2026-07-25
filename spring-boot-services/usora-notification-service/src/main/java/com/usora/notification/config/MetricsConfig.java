package com.usora.notification.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().commonTags("service", "usora-notification-service");
    }

    @Bean
    public ExecutorServiceMetrics virtualThreadMetrics() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        return ExecutorServiceMetrics.monitor(
                io.micrometer.core.instrument.Metrics.globalRegistry,
                executor,
                "notification-virtual-threads"
        );
    }
}
