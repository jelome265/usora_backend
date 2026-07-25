package com.usora.compliance.unit;

import com.usora.compliance.dto.RegulatoryRulesUpdateRequest;
import com.usora.compliance.mapper.EntityMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.*;

class MapperUnitTest {

    private final EntityMapper mapper = Mappers.getMapper(EntityMapper.class);

    @Test
    void shouldMapRuleRequest() {
        var request = new RegulatoryRulesUpdateRequest(
                "rule-1", "Test Rule", "desc", "eu_gdpr",
                "aml", "high", "drl content", true, null, null, null);
        var entity = mapper.toComplianceRule(request);
        assertNotNull(entity);
        assertEquals("rule-1", request.ruleId());
    }
}
