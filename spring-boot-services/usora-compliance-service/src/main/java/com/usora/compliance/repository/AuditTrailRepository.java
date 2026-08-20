package com.usora.compliance.repository;

import com.usora.compliance.entity.AuditTrailEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditTrailRepository extends JpaRepository<AuditTrailEntry, String> {

    // SECURITY: caseId is not guaranteed globally unique across tenants —
    // every lookup here must also be scoped by tenantId, or one tenant's
    // audit hash chain can pick up another tenant's entry as its
    // "previous" link (both a cross-tenant data leak and a corruption of
    // the tamper-evidence chain itself). Do not reintroduce a
    // tenant-unscoped variant of these methods.
    List<AuditTrailEntry> findByTenantIdAndCaseIdOrderByTimestampAsc(String tenantId, String caseId);

    Page<AuditTrailEntry> findByTenantIdAndEventType(String tenantId, String eventType, Pageable pageable);

    Page<AuditTrailEntry> findByTenantIdAndTimestampBetween(String tenantId, Instant start, Instant end, Pageable pageable);

    Optional<AuditTrailEntry> findTopByTenantIdAndCaseIdOrderByTimestampDesc(String tenantId, String caseId);

    @Query("SELECT a.currentHash FROM AuditTrailEntry a WHERE a.tenantId = :tenantId AND a.caseId = :caseId ORDER BY a.timestamp DESC")
    List<String> findHashChainByTenantIdAndCaseId(@Param("tenantId") String tenantId, @Param("caseId") String caseId);

    long countByTenantIdAndCaseId(String tenantId, String caseId);

    void deleteByCreatedAtBefore(Instant cutoff);
}
