package com.usora.compliance.repository;

import com.usora.compliance.entity.ComplianceRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class ComplianceRuleRepositoryTest {

    @Autowired
    private ComplianceRuleRepository ruleRepository;

    private ComplianceRule createRule(String ruleId, int version, String tenantId, String jurisdiction, boolean active) {
        var rule = new ComplianceRule();
        rule.setRuleId(ruleId);
        rule.setRuleVersion(version);
        rule.setName("Test Rule " + ruleId);
        rule.setTenantId(tenantId);
        rule.setJurisdiction(jurisdiction);
        rule.setSeverity("high");
        rule.setDrlContent("rule \"test\" when then end");
        rule.setActive(active);
        rule.setEffectiveFrom(Instant.now().minusSeconds(3600));
        rule.setExpiresAt(Instant.now().plusSeconds(86400));
        return ruleRepository.save(rule);
    }

    @BeforeEach
    void setUp() {
        ruleRepository.deleteAll();
        createRule("RULE001", 1, "tenant1", "eu_gdpr", true);
        createRule("RULE002", 1, "tenant1", "us_aml", true);
        createRule("RULE003", 1, "tenant2", "eu_gdpr", true);
        createRule("RULE004", 1, "tenant1", "eu_gdpr", false);
    }

    @Test
    void shouldFindActiveRulesForTenant() {
        var rules = ruleRepository.findActiveRulesForTenant("tenant1", Instant.now());
        assertEquals(2, rules.size());
    }

    @Test
    void shouldFindActiveRulesForTenantAndJurisdiction() {
        var rules = ruleRepository.findActiveRulesForTenantAndJurisdiction("tenant1", "eu_gdpr", Instant.now());
        assertEquals(1, rules.size());
        assertEquals("RULE001", rules.get(0).getRuleId());
    }

    @Test
    void shouldReturnEmptyForNonExistentTenant() {
        var rules = ruleRepository.findActiveRulesForTenant("nonexistent", Instant.now());
        assertTrue(rules.isEmpty());
    }

    @Test
    void shouldFindByRuleIdOrderByVersionDesc() {
        createRule("RULE001", 2, "tenant1", "eu_gdpr", true);
        var versions = ruleRepository.findByRuleIdOrderByRuleVersionDesc("RULE001");
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).getRuleVersion());
    }
}
