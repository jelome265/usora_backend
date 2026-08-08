package com.usora.audit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.audit.dto.RequestDto.AuditEventRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

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

    @Test
    void healthEndpointShouldReturnUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void logEventShouldReturnCreated() throws Exception {
        AuditEventRequest request = AuditEventRequest.builder()
                .timestamp(Instant.now())
                .actorId("integration-test-user")
                .action("INTEGRATION_TEST")
                .resourceType("API_TEST")
                .resourceId("api-test-1")
                .tenantId("default")
                .outcome("SUCCESS")
                .build();

        mockMvc.perform(post("/api/v1/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.currentHash").isNotEmpty())
                .andExpect(jsonPath("$.signature").isNotEmpty());
    }

    @Test
    void logEventShouldRequireAuth() throws Exception {
        AuditEventRequest request = AuditEventRequest.builder()
                .timestamp(Instant.now())
                .actorId("test-user")
                .action("TEST")
                .resourceType("TEST")
                .resourceId("test-1")
                .tenantId("default")
                .outcome("SUCCESS")
                .build();

        mockMvc.perform(post("/api/v1/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyIntegrityShouldReturnOk() throws Exception {
        String requestJson = """
                {
                    "tenantId": "default",
                    "fromTimestamp": "2020-01-01T00:00:00Z",
                    "toTimestamp": "2030-01-01T00:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/audit/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").isBoolean());
    }

    @Test
    void tamperAlertsShouldReturnOk() throws Exception {
        mockMvc.perform(get("/api/v1/audit/tamper-alerts")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }
}
