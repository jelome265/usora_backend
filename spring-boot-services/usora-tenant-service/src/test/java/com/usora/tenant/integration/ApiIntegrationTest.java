package com.usora.tenant.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.tenant.dto.OnboardRequest;
import com.usora.tenant.entity.TenantEntity;
import com.usora.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantRepository tenantRepository;

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockitoBean
    private KafkaAdmin kafkaAdmin;

    private UUID existingTenantId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tenantRepository.deleteAll();

        // Configure mock Kafka template responses for two-parameter and three-parameter send methods
        when(kafkaTemplate.send(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        TenantEntity tenant = new TenantEntity();
        tenant.setName("Existing Tenant");
        tenant.setDomain("existing.example.com");
        tenant.setPlan("business");
        tenant.setRegion("us-east");
        tenant.setStatus(TenantEntity.TenantStatus.ACTIVE);
        tenant.setAdminEmail("existing@example.com");
        existingTenantId = tenantRepository.save(tenant).getId();
    }

    @Test
    void healthEndpoint_shouldReturnOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("usora-tenant-service-test"))
                .andExpect(jsonPath("$.database.status").value("UP"));
    }

    @Test
    void onboardTenant_shouldReturn201() throws Exception {
        OnboardRequest request = OnboardRequest.builder()
                .name("Integration Tenant")
                .domain("integration-test.example.com")
                .plan("enterprise")
                .region("eu-west")
                .adminEmail("admin@integration.com")
                .features(Map.of("analytics", true, "api_access", true))
                .build();

        String json = objectMapper.writeValueAsString(request);

        MvcResult result = mockMvc.perform(post("/api/v1/tenants")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assert responseBody.contains("Integration Tenant");
    }

    @Test
    void getTenant_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", existingTenantId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Existing Tenant"))
                .andExpect(jsonPath("$.domain").value("existing.example.com"));
    }

    @Test
    void getTenant_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void listTenants_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/tenants")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void onboardTenant_shouldReturn409ForDuplicateDomain() throws Exception {
        OnboardRequest request = OnboardRequest.builder()
                .name("Duplicate")
                .domain("existing.example.com")
                .plan("free")
                .region("us-west")
                .adminEmail("dup@example.com")
                .build();

        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/tenants")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_ALREADY_EXISTS"));
    }

    @Test
    void onboardTenant_shouldReturn400ForMissingFields() throws Exception {
        mockMvc.perform(post("/api/v1/tenants")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getTenantStatus_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}/status", existingTenantId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
