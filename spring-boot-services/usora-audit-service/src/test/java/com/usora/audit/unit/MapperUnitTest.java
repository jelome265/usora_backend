package com.usora.audit.unit;

import com.usora.audit.dto.RequestDto.AuditEventRequest;
import com.usora.audit.dto.ResponseDto.AuditEventResponse;
import com.usora.audit.entity.AuditEvent;
import com.usora.audit.mapper.EntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("test")
class MapperUnitTest {

    @Autowired
    private EntityMapper entityMapper;

    @Test
    void shouldMapRequestToEntity() {
        AuditEventRequest request = AuditEventRequest.builder()
                .timestamp(Instant.now())
                .actorId("user-1")
                .action("LOGIN")
                .resourceType("USER_SESSION")
                .resourceId("session-1")
                .tenantId("test-tenant")
                .outcome("SUCCESS")
                .category("authentication")
                .severity("INFO")
                .ipAddress("192.168.1.1")
                .userAgent("Mozilla/5.0")
                .build();

        AuditEvent entity = entityMapper.toEntity(request);

        assertNotNull(entity);
        assertEquals(request.getActorId(), entity.getActorId());
        assertEquals(request.getAction(), entity.getAction());
        assertEquals(request.getResourceType(), entity.getResourceType());
        assertEquals(request.getResourceId(), entity.getResourceId());
        assertEquals(request.getTenantId(), entity.getTenantId());
        assertEquals(request.getOutcome(), entity.getOutcome());
        assertEquals(request.getCategory(), entity.getCategory());
        assertEquals(request.getSeverity(), entity.getSeverity());
        assertEquals(request.getIpAddress(), entity.getIpAddress());
        assertEquals(request.getUserAgent(), entity.getUserAgent());
        assertNull(entity.getId());
        assertNull(entity.getPreviousHash());
        assertNull(entity.getCurrentHash());
        assertNull(entity.getSignature());
    }

    @Test
    void shouldMapEntityToResponse() {
        UUID id = UUID.randomUUID();
        AuditEvent entity = new AuditEvent();
        entity.setId(id);
        entity.setTenantId("test-tenant");
        entity.setActorId("user-1");
        entity.setAction("LOGIN");
        entity.setResourceType("USER_SESSION");
        entity.setResourceId("session-1");
        entity.setOutcome("SUCCESS");
        entity.setCurrentHash("abc123");
        entity.setSignature("sig123");
        entity.setEventTimestamp(Instant.now());

        AuditEventResponse response = entityMapper.toResponse(entity);

        assertNotNull(response);
        assertEquals(id.toString(), response.getId());
        assertEquals(entity.getTenantId(), response.getTenantId());
        assertEquals(entity.getActorId(), response.getActorId());
        assertEquals(entity.getAction(), response.getAction());
        assertEquals(entity.getCurrentHash(), response.getCurrentHash());
        assertEquals(entity.getSignature(), response.getSignature());
    }

    @Test
    void shouldHandleNullIdInEntity() {
        AuditEvent entity = new AuditEvent();
        entity.setTenantId("test-tenant");
        entity.setActorId("user-1");
        entity.setAction("TEST");
        entity.setResourceType("TEST");
        entity.setResourceId("test-1");
        entity.setOutcome("SUCCESS");
        entity.setCurrentHash("hash");
        entity.setSignature("sig");
        entity.setEventTimestamp(Instant.now());

        AuditEventResponse response = entityMapper.toResponse(entity);

        assertNotNull(response);
        assertNull(response.getId());
    }
}
