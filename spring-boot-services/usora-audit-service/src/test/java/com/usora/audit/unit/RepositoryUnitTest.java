package com.usora.audit.unit;

import com.usora.audit.entity.AuditEvent;
import com.usora.audit.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
class RepositoryUnitTest {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void shouldSaveAndFindAuditEvent() {
        AuditEvent event = createTestEvent("test-tenant");
        AuditEvent saved = auditEventRepository.save(event);

        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void shouldFindEventsByTenantAndResource() {
        String tenantId = "tenant-res-" + UUID.randomUUID().toString().substring(0, 8);
        String resourceType = "ORDER";
        String resourceId = "order-1";

        AuditEvent event1 = createTestEvent(tenantId);
        event1.setResourceType(resourceType);
        event1.setResourceId(resourceId);
        auditEventRepository.save(event1);

        AuditEvent event2 = createTestEvent(tenantId);
        event2.setResourceType(resourceType);
        event2.setResourceId(resourceId);
        auditEventRepository.save(event2);

        List<AuditEvent> events = auditEventRepository.findByTenantIdAndResourceTypeAndResourceId(
                tenantId, resourceType, resourceId, PageRequest.of(0, 10));

        assertEquals(2, events.size());
    }

    @Test
    void shouldFindEventsByTenantAndTimestampRange() {
        String tenantId = "tenant-ts-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();

        auditEventRepository.save(createTestEvent(tenantId, now.minusSeconds(3600)));
        auditEventRepository.save(createTestEvent(tenantId, now));
        auditEventRepository.save(createTestEvent(tenantId, now.plusSeconds(3600)));

        List<AuditEvent> events = auditEventRepository.findByTenantIdAndTimestampBetweenOrdered(
                tenantId, now.minusSeconds(1800), now.plusSeconds(1800));

        assertEquals(1, events.size());
    }

    @Test
    void shouldFindTopByTenantIdAndAnchored() {
        String tenantId = "tenant-anchor-" + UUID.randomUUID().toString().substring(0, 8);

        AuditEvent event1 = createTestEvent(tenantId);
        event1.setAnchored(false);
        auditEventRepository.save(event1);

        AuditEvent event2 = createTestEvent(tenantId);
        event2.setAnchored(true);
        auditEventRepository.save(event2);

        Optional<AuditEvent> found = auditEventRepository
                .findTopByTenantIdAndAnchoredOrderByEventTimestampDesc(tenantId, false);

        assertTrue(found.isPresent());
        assertFalse(found.get().isAnchored());
    }

    @Test
    void shouldReturnEmptyForNonExistentHash() {
        Optional<AuditEvent> found = auditEventRepository
                .findByTenantIdAndCurrentHash("nonexistent", "hash123");

        assertFalse(found.isPresent());
    }

    private AuditEvent createTestEvent(String tenantId) {
        return createTestEvent(tenantId, Instant.now());
    }

    private AuditEvent createTestEvent(String tenantId, Instant timestamp) {
        AuditEvent event = new AuditEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setEventType("TEST_EVENT");
        event.setActorId("test-user");
        event.setAction("TEST_ACTION");
        event.setResourceType("TEST");
        event.setResourceId("test-resource");
        event.setOutcome("SUCCESS");
        event.setCurrentHash(UUID.randomUUID().toString().replace("-", ""));
        event.setSignature(UUID.randomUUID().toString().replace("-", ""));
        event.setEventTimestamp(timestamp);
        return event;
    }
}
