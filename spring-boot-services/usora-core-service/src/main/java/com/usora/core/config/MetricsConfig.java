package com.usora.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistry meterRegistry() {
        return new CompositeMeterRegistry();
    }

    @Bean
    public ObservationRegistryCustomizer<?> observationRegistryCustomizer() {
        return (ObservationRegistry registry) -> registry.observationConfig()
                .observationHandler(observation -> {});
    }
}
