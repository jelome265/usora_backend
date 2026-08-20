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

    // SECURITY: every lookup by ruleId MUST also be scoped by tenantId.
    // ruleId is a logical/human-assigned identifier, not a globally unique
    // value guaranteed distinct across tenants — a tenant-unscoped lookup
    // here is a cross-tenant IDOR (a caller from tenant A can read/chain off
    // tenant B's rule row for a same-named ruleId). Do not reintroduce a
    // tenant-unscoped variant of these methods.
    Optional<ComplianceRule> findTopByTenantIdAndRuleIdOrderByRuleVersionDesc(String tenantId, String ruleId);

    List<ComplianceRule> findByTenantIdAndRuleIdOrderByRuleVersionDesc(String tenantId, String ruleId);

    @Query("SELECT r FROM ComplianceRule r WHERE r.tenantId = :tenantId AND r.active = true AND r.effectiveFrom <= :now AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
    List<ComplianceRule> findActiveRulesForTenant(@Param("tenantId") String tenantId, @Param("now") Instant now);

    @Query("SELECT r FROM ComplianceRule r WHERE r.tenantId = :tenantId AND r.jurisdiction = :jurisdiction AND r.active = true AND r.effectiveFrom <= :now AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
    List<ComplianceRule> findActiveRulesForTenantAndJurisdiction(@Param("tenantId") String tenantId, @Param("jurisdiction") String jurisdiction, @Param("now") Instant now);

    boolean existsByTenantIdAndRuleIdAndActiveTrue(String tenantId, String ruleId);
}
