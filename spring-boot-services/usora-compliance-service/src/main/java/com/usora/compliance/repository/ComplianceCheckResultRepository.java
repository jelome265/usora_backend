package com.usora.compliance.repository;

import com.usora.compliance.entity.ComplianceCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ComplianceCheckResultRepository extends JpaRepository<ComplianceCheckResult, String> {

    // SECURITY: scope every caseId lookup by tenantId — see
    // AuditTrailRepository for the same rule and the reasoning.
    List<ComplianceCheckResult> findByTenantIdAndCaseIdOrderByValidatedAtDesc(String tenantId, String caseId);

    Optional<ComplianceCheckResult> findTopByTenantIdAndCaseIdOrderByValidatedAtDesc(String tenantId, String caseId);

    List<ComplianceCheckResult> findByTenantIdAndCaseId(String tenantId, String caseId);

    List<ComplianceCheckResult> findByTenantIdAndOverallDecision(String tenantId, String overallDecision);

    List<ComplianceCheckResult> findByValidatedAtBefore(Instant cutoff);
}
