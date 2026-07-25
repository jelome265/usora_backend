package com.usora.compliance.repository;

import com.usora.compliance.entity.ComplianceRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ComplianceRuleRepository extends JpaRepository<ComplianceRule, String> {

    List<ComplianceRule> findByTenantIdAndActiveTrue(String tenantId);

    List<ComplianceRule> findByTenantIdAndJurisdictionAndActiveTrue(String tenantId, String jurisdiction);

    Optional<ComplianceRule> findTopByRuleIdOrderByRuleVersionDesc(String ruleId);

    List<ComplianceRule> findByRuleIdOrderByRuleVersionDesc(String ruleId);

    @Query("SELECT r FROM ComplianceRule r WHERE r.tenantId = :tenantId AND r.active = true AND r.effectiveFrom <= :now AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
    List<ComplianceRule> findActiveRulesForTenant(@Param("tenantId") String tenantId, @Param("now") Instant now);

    @Query("SELECT r FROM ComplianceRule r WHERE r.tenantId = :tenantId AND r.jurisdiction = :jurisdiction AND r.active = true AND r.effectiveFrom <= :now AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
    List<ComplianceRule> findActiveRulesForTenantAndJurisdiction(@Param("tenantId") String tenantId, @Param("jurisdiction") String jurisdiction, @Param("now") Instant now);

    boolean existsByRuleIdAndActiveTrue(String ruleId);
}
