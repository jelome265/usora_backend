package com.usora.tenant.controller;

import com.usora.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final DataSource dataSource;
    private final TenantRepository tenantRepository;

    @Value("${spring.application.name:usora-tenant-service}")
    private String applicationName;

    public HealthController(DataSource dataSource, TenantRepository tenantRepository) {
        this.dataSource = dataSource;
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", applicationName);
        status.put("timestamp", Instant.now().toString());

        Map<String, Object> db = new HashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            db.put("status", "UP");
            db.put("database", conn.getMetaData().getDatabaseProductName());
            db.put("version", conn.getMetaData().getDatabaseProductVersion());
        } catch (Exception e) {
            db.put("status", "DOWN");
            db.put("error", e.getMessage());
            status.put("status", "DEGRADED");
        }
        status.put("database", db);

        try {
            long tenantCount = tenantRepository.count();
            status.put("tenantCount", tenantCount);
        } catch (Exception e) {
            status.put("tenantCount", -1);
        }

        return ResponseEntity.ok(status);
    }
}
