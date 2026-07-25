package com.usora.notification.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping({"/actuator/health", "/actuator/health/liveness"})
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "usora-notification-service",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @GetMapping("/actuator/health/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "usora-notification-service",
                "ready", true
        ));
    }
}
