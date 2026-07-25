package com.usora.core.job;

import com.usora.core.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ScheduledCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupJob.class);

    private final JdbcTemplate jdbcTemplate;

    public ScheduledCleanupJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 */6 * * ?")
    public void cleanupExpiredVerifications() {
        log.info("Starting expired verification cleanup at {}", Instant.now());
        try {
            var deleted = jdbcTemplate.update(
                    "UPDATE cases SET status = 'EXPIRED', updated_at = NOW() " +
                    "WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '30 days'"
            );
            log.info("Expired verification cleanup completed: {} cases expired", deleted);
        } catch (Exception e) {
            log.error("Failed to cleanup expired verifications", e);
        }
    }

    @Scheduled(fixedRate = 300000)
    public void detectSLABreaches() {
        log.info("Checking for SLA breaches at {}", Instant.now());
        try {
            var breaches = jdbcTemplate.queryForList(
                    "SELECT id, tenant_id, created_at FROM cases " +
                    "WHERE status = 'IN_PROGRESS' AND created_at < NOW() - INTERVAL '24 hours'"
            );
            if (!breaches.isEmpty()) {
                log.warn("SLA breach detected: {} cases exceeding 24h threshold", breaches.size());
                for (var breach : breaches) {
                    log.warn("SLA breach - caseId={}, tenantId={}, createdAt={}",
                            breach.get("id"), breach.get("tenant_id"), breach.get("created_at"));
                }
            }
        } catch (Exception e) {
            log.error("Failed to detect SLA breaches", e);
        }
    }
}
