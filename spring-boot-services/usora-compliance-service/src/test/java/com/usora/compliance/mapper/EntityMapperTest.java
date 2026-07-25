package com.usora.compliance.mapper;

import com.usora.compliance.dto.EvidenceSubmissionRequest;
import com.usora.compliance.dto.RegulatoryRulesUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class EntityMapperTest {

    @Autowired
    private EntityMapper entityMapper;

    @Test
    void shouldMapRuleUpdateRequestToEntity() {
        var request = new RegulatoryRulesUpdateRequest(
                "rule-001", "AML Check", "Checks AML compliance",
                "eu_gdpr", "aml", "critical",
                "rule \"AML Check\" when then end", true,
                null, null, null);

        var entity = entityMapper.toComplianceRule(request);

        assertNotNull(entity);
        assertEquals("rule-001", request.ruleId());
        assertEquals("AML Check", request.name());
        assertEquals("critical", request.severity());
    }

    @Test
    void shouldMapEvidenceRequestToEntity() {
        var request = new EvidenceSubmissionRequest(
                "case-001", "document", "abc123",
                "test content".getBytes(), "text/plain",
                null, null, false);

        var entity = entityMapper.toEvidenceRecord(request);

        assertNotNull(entity);
        assertEquals("case-001", request.caseId());
        assertEquals("document", request.evidenceType());
        assertArrayEquals("test content".getBytes(), request.content());
    }
}
