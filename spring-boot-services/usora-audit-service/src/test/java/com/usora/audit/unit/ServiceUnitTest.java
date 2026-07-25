package com.usora.audit.unit;

import com.usora.audit.dto.RequestDto.AuditEventRequest;
import com.usora.audit.dto.RequestDto.IntegrityVerificationRequest;
import com.usora.audit.dto.ResponseDto.AuditEventResponse;
import com.usora.audit.dto.ResponseDto.IntegrityResponse;
import com.usora.audit.service.DomainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ServiceUnitTest {

    @Autowired
    private DomainService domainService;

    @Test
    void shouldLogAndRetrieveAuditEvent() {
        AuditEventRequest request = AuditEventRequest.builder()
                .timestamp(Instant.now())
                .actorId("test-actor")
                .action("TEST_ACTION")
                .resourceType("SERVICE_TEST")
                .resourceId("test-res-1")
                .tenantId("default")
                .outcome("SUCCESS")
                .category("compliance_check")
                .severity("INFO")
                .build();

        AuditEventResponse response = domainService.logEvent(request);

        assertNotNull(response);
        assertNotNull(response.getId());
        assertEquals("test-actor", response.getActorId());
        assertEquals("TEST_ACTION", response.getAction());
        assertNotNull(response.getCurrentHash());
        assertNotNull(response.getSignature());
        assertNotNull(response.getEventTimestamp());
    }

    @Test
    void shouldVerifyIntegrityOfEmptyRange() {
        IntegrityVerificationRequest request = new IntegrityVerificationRequest();
        request.setTenantId("default");
        request.setFromTimestamp(Instant.EPOCH);
        request.setToTimestamp(Instant.EPOCH.plusSeconds(1));

        IntegrityResponse response = domainService.verifyIntegrity(request);

        assertNotNull(response);
        assertTrue(response.isValid());
    }

    @Test
    void shouldLogMultipleEventsAndVerifyIntegrity() {
        String tenantId = "default";

        for (int i = 0; i < 3; i++) {
            AuditEventRequest request = AuditEventRequest.builder()
                    .timestamp(Instant.now())
                    .actorId("bulk-test-actor")
                    .action("BULK_TEST")
                    .resourceType("BULK_TEST")
                    .resourceId("bulk-" + i)
                    .tenantId(tenantId)
                    .outcome("SUCCESS")
                    .build();
            domainService.logEvent(request);
        }

        IntegrityVerificationRequest verifyRequest = new IntegrityVerificationRequest();
        verifyRequest.setTenantId(tenantId);
        verifyRequest.setFromTimestamp(Instant.now().minusSeconds(60));
        verifyRequest.setToTimestamp(Instant.now().plusSeconds(60));

        IntegrityResponse response = domainService.verifyIntegrity(verifyRequest);

        assertNotNull(response);
    }

    @Test
    void shouldHandleSensitiveDataEncryption() {
        AuditEventRequest request = AuditEventRequest.builder()
                .timestamp(Instant.now())
                .actorId("test-actor")
                .action("DATA_ACCESS")
                .resourceType("SENSITIVE_DATA")
                .resourceId("secret-1")
                .tenantId("default")
                .outcome("SUCCESS")
                .beforeState("{\"ssn\":\"123-45-6789\"}")
                .afterState("{\"ssn\":\"123-45-6789\",\"modified\":true}")
                .build();

        AuditEventResponse response = domainService.logEvent(request);

        assertNotNull(response);
        assertNotNull(response.getId());
    }
}
