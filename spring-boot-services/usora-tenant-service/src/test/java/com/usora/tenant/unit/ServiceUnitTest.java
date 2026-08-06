package com.usora.tenant.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.tenant.config.TenantConfig;
import com.usora.tenant.dto.OnboardRequest;
import com.usora.tenant.dto.SuspendRequest;
import com.usora.tenant.dto.TenantResponse;
import com.usora.tenant.entity.TenantEntity;
import com.usora.tenant.event.DomainEventPublisher;
import com.usora.tenant.exception.BusinessException;
import com.usora.tenant.mapper.EntityMapper;
import com.usora.tenant.repository.TenantRepository;
import com.usora.tenant.service.DomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceUnitTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private EntityMapper entityMapper;
    @Mock
    private TenantConfig tenantConfig;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private KafkaAdmin kafkaAdmin;

    private ObjectMapper objectMapper;
    private DomainService domainService;

    private TenantEntity testEntity;
    private TenantConfig.Provisioning provisioning;
    private TenantConfig.Offboarding offboarding;
    private EntityMapper realEntityMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        domainService = new DomainService(tenantRepository, entityMapper, tenantConfig, eventPublisher, objectMapper, jdbcTemplate, kafkaTemplate, kafkaAdmin);

        // EntityMapperImpl (the real MapStruct-generated implementation) has
        // its own @Autowired ObjectMapper dependency (from
        // @Mapper(uses = ObjectMapper.class)) that Mappers.getMapper()
        // alone doesn't satisfy, since that bypasses Spring's container
        // entirely. Construct it once and inject that dependency manually
        // via ReflectionTestUtils -- the standard way to satisfy a
        // component's injected fields in a test that doesn't load a full
        // Spring context.
        realEntityMapper = Mappers.getMapper(EntityMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(realEntityMapper, "objectMapper", objectMapper);

        testEntity = new TenantEntity();
        testEntity.setId(UUID.randomUUID());
        testEntity.setName("Test Tenant");
        testEntity.setDomain("test.example.com");
        testEntity.setPlan("business");
        testEntity.setRegion("us-east");
        testEntity.setStatus(TenantEntity.TenantStatus.ACTIVE);
        testEntity.setAdminEmail("admin@test.com");
        testEntity.setMaxUsers(100);
        testEntity.setStorageQuotaBytes(107374182400L);
        testEntity.setCreatedAt(Instant.now());
        testEntity.setUpdatedAt(Instant.now());

        provisioning = new TenantConfig.Provisioning();
        provisioning.setSchemaPrefix("tenant_");
        provisioning.setNamespacePrefix("tenant-");
        offboarding = new TenantConfig.Offboarding();
        offboarding.setGdprCompliance(true);
        offboarding.setPurgeImmediately(true);

        lenient().when(tenantConfig.getProvisioning()).thenReturn(provisioning);
        lenient().when(tenantConfig.getOffboarding()).thenReturn(offboarding);
    }

    @Test
    void shouldOnboardTenantSuccessfully() {
        OnboardRequest request = OnboardRequest.builder()
                .name("New Tenant")
                .domain("new.example.com")
                .plan("starter")
                .region("eu-west")
                .adminEmail("admin@new.com")
                .build();

        TenantEntity newEntity = new TenantEntity();
        newEntity.setId(UUID.randomUUID());
        newEntity.setName("New Tenant");
        newEntity.setDomain("new.example.com");
        newEntity.setPlan("starter");
        newEntity.setRegion("eu-west");
        newEntity.setStatus(TenantEntity.TenantStatus.PROVISIONING);
        newEntity.setAdminEmail("admin@new.com");

        when(tenantRepository.existsByDomain("new.example.com")).thenReturn(false);
        when(entityMapper.toEntity(request)).thenReturn(newEntity);
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(i -> {
            TenantEntity e = i.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(entityMapper.toResponse(any())).thenAnswer(inv -> realEntityMapper.toResponse(inv.getArgument(0)));

        TenantResponse response = assertDoesNotThrow(() -> domainService.onboardTenant(request));

        assertNotNull(response);
        assertEquals("new.example.com", response.getDomain());
        verify(eventPublisher).publishTenantProvisioned(any());
    }

    @Test
    void shouldThrowWhenDomainExists() {
        OnboardRequest request = OnboardRequest.builder()
                .name("Duplicate")
                .domain("test.example.com")
                .plan("free")
                .region("us-east")
                .adminEmail("dup@test.com")
                .build();

        when(tenantRepository.existsByDomain("test.example.com")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> domainService.onboardTenant(request));
        assertEquals("TENANT_ALREADY_EXISTS", ex.getErrorCode());
    }

    @Test
    void shouldGetTenantById() {
        when(tenantRepository.findById(testEntity.getId())).thenReturn(Optional.of(testEntity));
        when(entityMapper.toResponse(testEntity)).thenAnswer(inv -> realEntityMapper.toResponse(inv.getArgument(0)));

        assertDoesNotThrow(() -> domainService.getTenant(testEntity.getId()));
    }

    @Test
    void shouldThrowWhenTenantNotFound() {
        UUID randomId = UUID.randomUUID();
        when(tenantRepository.findById(randomId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> domainService.getTenant(randomId));
        assertEquals("TENANT_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void shouldSuspendActiveTenant() {
        SuspendRequest request = new SuspendRequest("Payment failure");
        when(tenantRepository.findById(testEntity.getId())).thenReturn(Optional.of(testEntity));
        when(tenantRepository.save(any(TenantEntity.class))).thenReturn(testEntity);
        when(entityMapper.toResponse(any())).thenAnswer(inv -> realEntityMapper.toResponse(inv.getArgument(0)));

        assertDoesNotThrow(() -> domainService.suspendTenant(testEntity.getId(), request));
        verify(eventPublisher).publishTenantSuspended(any(), eq("Payment failure"));
    }

    @Test
    void shouldThrowWhenSuspendingAlreadySuspendedTenant() {
        testEntity.setStatus(TenantEntity.TenantStatus.SUSPENDED);
        SuspendRequest request = new SuspendRequest("Already suspended");
        when(tenantRepository.findById(testEntity.getId())).thenReturn(Optional.of(testEntity));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> domainService.suspendTenant(testEntity.getId(), request));
        assertTrue(ex.getMessage().contains("already suspended"));
    }

    @Test
    void shouldResumeSuspendedTenant() {
        testEntity.setStatus(TenantEntity.TenantStatus.SUSPENDED);
        when(tenantRepository.findById(testEntity.getId())).thenReturn(Optional.of(testEntity));
        when(tenantRepository.save(any(TenantEntity.class))).thenReturn(testEntity);
        when(entityMapper.toResponse(any())).thenAnswer(inv -> realEntityMapper.toResponse(inv.getArgument(0)));

        assertDoesNotThrow(() -> domainService.resumeTenant(testEntity.getId()));
        verify(eventPublisher).publishTenantResumed(any());
    }

    @Test
    void shouldThrowWhenResumingNonSuspendedTenant() {
        when(tenantRepository.findById(testEntity.getId())).thenReturn(Optional.of(testEntity));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> domainService.resumeTenant(testEntity.getId()));
        assertTrue(ex.getMessage().contains("not suspended"));
    }

    @Test
    void shouldThrowWhenOffboardingAlreadyDeletedTenant() {
        testEntity.setStatus(TenantEntity.TenantStatus.DELETED);
        when(tenantRepository.findById(testEntity.getId())).thenReturn(Optional.of(testEntity));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> domainService.offboardTenant(testEntity.getId()));
        assertTrue(ex.getMessage().contains("already offboarded"));
    }
}
