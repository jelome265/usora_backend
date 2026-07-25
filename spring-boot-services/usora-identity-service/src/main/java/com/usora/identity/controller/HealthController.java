package com.usora.identity.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "version", "1.0.0",
                "uptime", formatUptime(uptime),
                "timestamp", Instant.now().toString()
        ));
    }

    private String formatUptime(long millis) {
        long hours = millis / 3600000;
        long minutes = (millis % 3600000) / 60000;
        return String.format("%dh%dm", hours, minutes);
    }
}
