package com.usora.core.unit;

import com.usora.core.dto.RequestDto;
import com.usora.core.entity.TenantEntity;
import com.usora.core.mapper.EntityMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MapperUnitTest {

    private final EntityMapper mapper = Mappers.getMapper(EntityMapper.class);

    @Test
    void shouldMapToSubmissionResponse() {
        var caseId = UUID.randomUUID();
        var response = mapper.toSubmissionResponse(caseId);

        assertNotNull(response);
        assertEquals(caseId, response.caseId());
        assertEquals("PENDING", response.status());
    }

    @Test
    void shouldMapToTenantConfigResponse() {
        var entity = new TenantEntity("tenant-1", Map.of("key", "value"));
        entity.setUpdatedAt(Instant.now());

        var response = mapper.toTenantConfigResponse(entity);

        assertNotNull(response);
        assertEquals("tenant-1", response.tenantId());
    }
}
