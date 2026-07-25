package com.usora.compliance.repository;

import com.usora.compliance.entity.ComplianceCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ComplianceCheckResultRepository extends JpaRepository<ComplianceCheckResult, String> {

    List<ComplianceCheckResult> findByCaseIdOrderByValidatedAtDesc(String caseId);

    Optional<ComplianceCheckResult> findTopByCaseIdOrderByValidatedAtDesc(String caseId);

    List<ComplianceCheckResult> findByTenantIdAndCaseId(String tenantId, String caseId);

    List<ComplianceCheckResult> findByTenantIdAndOverallDecision(String tenantId, String overallDecision);

    List<ComplianceCheckResult> findByValidatedAtBefore(Instant cutoff);
}
