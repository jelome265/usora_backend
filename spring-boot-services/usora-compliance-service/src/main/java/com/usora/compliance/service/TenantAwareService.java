package com.usora.compliance.service;

import com.usora.compliance.entity.ComplianceRule;
import com.usora.compliance.entity.JurisdictionConfig;
import com.usora.compliance.repository.ComplianceRuleRepository;
import com.usora.compliance.repository.JurisdictionConfigRepository;
import com.usora.compliance.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TenantAwareService {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareService.class);

    private final ComplianceRuleRepository ruleRepository;
    private final JurisdictionConfigRepository jurisdictionRepository;

    public TenantAwareService(ComplianceRuleRepository ruleRepository,
                              JurisdictionConfigRepository jurisdictionRepository) {
        this.ruleRepository = ruleRepository;
        this.jurisdictionRepository = jurisdictionRepository;
    }

    public List<ComplianceRule> getRulesForCurrentTenant() {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return List.of();
        return ruleRepository.findActiveRulesForTenant(tenantId, Instant.now());
    }

    public List<ComplianceRule> getRulesForTenantAndJurisdiction(String jurisdiction) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return List.of();
        return ruleRepository.findActiveRulesForTenantAndJurisdiction(tenantId, jurisdiction, Instant.now());
    }

    public JurisdictionConfig getJurisdictionConfig(String jurisdictionCode) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return null;
        return jurisdictionRepository.findByTenantIdAndJurisdictionCode(tenantId, jurisdictionCode).orElse(null);
    }

    public List<JurisdictionConfig> getAllJurisdictionConfigs() {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return List.of();
        return jurisdictionRepository.findByTenantIdAndActiveTrue(tenantId);
    }

    public boolean isTenantConfigured() {
        return TenantContext.getCurrentTenant() != null;
    }

    public void validateTenantAccess(String targetTenantId) {
        var currentTenant = TenantContext.getCurrentTenant();
        if (currentTenant == null || !currentTenant.equals(targetTenantId)) {
            throw new SecurityException("Tenant access violation: " + currentTenant + " cannot access " + targetTenantId);
        }
    }
}
