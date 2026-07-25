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

    List<AuditTrailEntry> findByCaseIdOrderByTimestampAsc(String caseId);

    List<AuditTrailEntry> findByTenantIdAndCaseIdOrderByTimestampAsc(String tenantId, String caseId);

    Page<AuditTrailEntry> findByTenantIdAndEventType(String tenantId, String eventType, Pageable pageable);

    Page<AuditTrailEntry> findByTenantIdAndTimestampBetween(String tenantId, Instant start, Instant end, Pageable pageable);

    Optional<AuditTrailEntry> findTopByCaseIdOrderByTimestampDesc(String caseId);

    @Query("SELECT a.currentHash FROM AuditTrailEntry a WHERE a.caseId = :caseId ORDER BY a.timestamp DESC")
    List<String> findHashChainByCaseId(@Param("caseId") String caseId);

    long countByTenantIdAndCaseId(String tenantId, String caseId);

    void deleteByCreatedAtBefore(Instant cutoff);
}
