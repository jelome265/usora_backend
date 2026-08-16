package com.usora.core.unit;

import com.usora.core.dto.RequestDto;
import com.usora.core.dto.RequestDto.KYCSubmissionRequest;
import com.usora.core.entity.TenantEntity;
import com.usora.core.event.DomainEventPublisher;
import com.usora.core.mapper.EntityMapper;
import com.usora.core.repository.TenantRepository;
import com.usora.core.security.TenantContext;
import com.usora.core.service.DomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceUnitTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private EntityMapper entityMapper;

    @Mock
    private DomainEventPublisher eventPublisher;

    private DomainService domainService;

    @BeforeEach
    void setUp() {
        domainService = new DomainService(jdbcTemplate, tenantRepository, entityMapper, eventPublisher);
        TenantContext.setCurrentTenantId("test-tenant");
    }

    @Test
    void shouldSubmitKYC() {
        var request = new KYCSubmissionRequest(
                "test-tenant", "cust-123",
                new KYCSubmissionRequest.Document("PASSPORT", "AB123", "US", "http://img.url", null),
                new KYCSubmissionRequest.Biometric("http://selfie.url", "http://video.url"),
                Map.of()
        );

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        var response = domainService.submitKYC(request);

        assertNotNull(response);
        assertEquals("PENDING", response.status());
        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void shouldGetTenantConfig() {
        var entity = new TenantEntity("test-tenant", Map.of("key", "value"));

        when(tenantRepository.findByTenantId("test-tenant")).thenReturn(Optional.of(entity));

        assertDoesNotThrow(() -> domainService.getTenantConfig("test-tenant"));
    }
}
