package com.usora.compliance.dto;

import java.time.Instant;
import java.util.List;

public record AuditTrailResponse(
        String caseId,
        String tenantId,
        List<AuditEntry> entries,
        Integer totalEntries,
        String hashChainRoot,
        Boolean integrityVerified
) {
    public record AuditEntry(
            String entryId,
            String eventType,
            String action,
            String actor,
            String tenantId,
            String description,
            Map<String, Object> details,
            String previousHash,
            String currentHash,
            Instant timestamp
    ) {
        public record Map<K, V> {}
    }
}
