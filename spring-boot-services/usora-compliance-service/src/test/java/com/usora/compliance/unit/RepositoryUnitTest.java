package com.usora.compliance.unit;

import com.usora.compliance.entity.ComplianceRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryUnitTest {

    @Test
    void shouldCreateComplianceRuleEntity() {
        var rule = new ComplianceRule();
        rule.setRuleId("RULE-001");
        rule.setRuleVersion(1);
        rule.setName("Test AML Rule");
        rule.setTenantId("tenant1");
        rule.setSeverity("high");
        rule.setDrlContent("rule \"test\" when then end");
        rule.setActive(true);
        rule.setEffectiveFrom(Instant.now());

        assertEquals("RULE-001", rule.getRuleId());
        assertEquals(1, rule.getRuleVersion());
        assertTrue(rule.getActive());
    }

    @Test
    void shouldSetTimestampsOnPrePersist() {
        var rule = new ComplianceRule();
        rule.onCreate();
        assertNotNull(rule.getCreatedAt());
        assertNotNull(rule.getUpdatedAt());
        assertNotNull(rule.getId());
    }
}
