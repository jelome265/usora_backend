package com.usora.compliance.service;

import com.usora.compliance.dto.*;
import com.usora.compliance.entity.ComplianceRule;
import com.usora.compliance.entity.EvidenceRecord;
import com.usora.compliance.entity.JurisdictionConfig;
import com.usora.compliance.event.DomainEventPublisher;
import com.usora.compliance.exception.BusinessException;
import com.usora.compliance.mapper.EntityMapper;
import com.usora.compliance.repository.*;
import com.usora.compliance.security.TenantContext;
import com.usora.compliance.util.HashingUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DomainServiceTest {

    @Mock private ComplianceRuleRepository ruleRepository;
    @Mock private EvidenceRecordRepository evidenceRepository;
    @Mock private JurisdictionConfigRepository jurisdictionRepository;
    @Mock private AuditTrailRepository auditTrailRepository;
    @Mock private ComplianceCheckResultRepository checkResultRepository;
    @Mock private EntityMapper entityMapper;
    @Mock private DomainEventPublisher eventPublisher;

    private DomainService domainService;

    @BeforeEach
    void setUp() {
        domainService = new DomainService(ruleRepository, evidenceRepository,
                jurisdictionRepository, auditTrailRepository, checkResultRepository,
                entityMapper, eventPublisher, null, null, null, null);
        TenantContext.setCurrentTenant("tenant1");
    }

    @Test
    void shouldThrowWhenNoTenantContext() {
        TenantContext.clear();
        var request = new ComplianceValidationRequest("case1", "entity1", "individual",
                Map.of(), null, null, null, null);
        assertThrows(BusinessException.class, () -> domainService.validateCompliance(request));
    }

    @Test
    void shouldSubmitEvidence() {
        var request = new EvidenceSubmissionRequest("case1", "document",
                HashingUtil.sha256("content".getBytes()), "content".getBytes(),
                "text/plain", null, null, false);
        var evidence = new EvidenceRecord();
        evidence.setId("ev-1");

        when(entityMapper.toEvidenceRecord(any())).thenReturn(evidence);
        when(evidenceRepository.save(any())).thenReturn(evidence);

        var response = domainService.submitEvidence(request);

        assertNotNull(response);
        assertEquals("SUBMITTED", response.status());
    }

    @Test
    void shouldThrowOnEvidenceHashMismatch() {
        var request = new EvidenceSubmissionRequest("case1", "document",
                "wronghash", "content".getBytes(),
                "text/plain", null, null, false);

        assertThrows(BusinessException.class, () -> domainService.submitEvidence(request));
    }

    @Test
    void shouldGetRegulatoryRules() {
        var rules = List.of(createMockRule("RULE1", "tenant1", "eu_gdpr"));
        when(ruleRepository.findActiveRulesForTenant(any(), any())).thenReturn(rules);

        var response = domainService.getRegulatoryRules(null);

        assertNotNull(response);
        assertEquals(1, response.totalRules());
    }

    @Test
    void shouldCheckJurisdictionCompliance() {
        var config = new JurisdictionConfig();
        config.setJurisdictionCode("eu_gdpr");
        config.setRequirePepCheck(true);
        config.setRequireAdverseMedia(false);

        when(jurisdictionRepository.findByTenantIdAndJurisdictionCode("tenant1", "eu_gdpr"))
                .thenReturn(Optional.of(config));

        var request = new JurisdictionCheckRequest("case1", "entity1", "eu_gdpr",
                Map.of("pepCheckCompleted", true, "kycVerified", true), List.of("GDPR"));

        assertThrows(BusinessException.class, () -> domainService.checkJurisdictionCompliance(request));
    }

    private ComplianceRule createMockRule(String ruleId, String tenantId, String jurisdiction) {
        var rule = new ComplianceRule();
        rule.setRuleId(ruleId);
        rule.setRuleVersion(1);
        rule.setName("Test");
        rule.setTenantId(tenantId);
        rule.setJurisdiction(jurisdiction);
        rule.setSeverity("high");
        rule.setDrlContent("rule \"test\" when then end");
        rule.setActive(true);
        return rule;
    }
}
