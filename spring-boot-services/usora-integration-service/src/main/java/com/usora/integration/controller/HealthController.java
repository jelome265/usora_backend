package com.usora.integration.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @Value("${integration.service.name:usora-integration-service}")
    private String serviceName;

    @Value("${integration.service.version:1.0.0-RC1}")
    private String serviceVersion;

    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", serviceName,
                "version", serviceVersion,
                "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/actuator/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "service", serviceName,
                "version", serviceVersion,
                "java", Runtime.version().toString(),
                "processors", Runtime.getRuntime().availableProcessors(),
                "maxMemory", Runtime.getRuntime().maxMemory(),
                "startedAt", Instant.now().toString()
        ));
    }
}
