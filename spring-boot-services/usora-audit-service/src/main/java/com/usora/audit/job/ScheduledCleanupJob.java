package com.usora.audit.job;

import com.usora.audit.config.TenantConfig;
import com.usora.audit.service.DomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ScheduledCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupJob.class);

    private final DomainService domainService;
    private final TenantConfig tenantConfig;

    public ScheduledCleanupJob(DomainService domainService, TenantConfig tenantConfig) {
        this.domainService = domainService;
        this.tenantConfig = tenantConfig;
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS, initialDelay = 5)
    public void computeMerkleRoots() {
        log.info("Starting hourly Merkle root computation for all tenants");
        for (String tenantId : tenantConfig.getTenants().keySet()) {
            try {
                domainService.computeAndAnchorMerkleRoot(tenantId);
            } catch (Exception e) {
                log.error("Merkle root computation failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
        log.info("Hourly Merkle root computation completed");
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.DAYS, initialDelay = 10)
    public void archiveOldEvents() {
        log.info("Starting daily cold storage archiving for all tenants");
        for (String tenantId : tenantConfig.getTenants().keySet()) {
            try {
                domainService.archiveOldEvents(tenantId);
            } catch (Exception e) {
                log.error("Archiving failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
        log.info("Daily cold storage archiving completed");
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS, initialDelay = 15)
    public void integrityVerification() {
        log.info("Starting periodic integrity verification for all tenants");
        for (String tenantId : tenantConfig.getTenants().keySet()) {
            try {
                var request = new com.usora.audit.dto.RequestDto.IntegrityVerificationRequest();
                request.setTenantId(tenantId);
                domainService.verifyIntegrity(request);
            } catch (Exception e) {
                log.error("Integrity verification failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
        log.info("Periodic integrity verification completed");
    }
}
