package com.usora.core.integration;

import com.usora.core.dto.RequestDto;
import com.usora.core.dto.RequestDto.KYCSubmissionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = "SCOPE_kyc:submit")
    void shouldSubmitKYC() throws Exception {
        var request = new KYCSubmissionRequest(
                "test-tenant", "cust-001",
                new KYCSubmissionRequest.Document("PASSPORT", "AB123", "US", "http://img.url", null),
                new KYCSubmissionRequest.Biometric("http://selfie.url", "http://video.url"),
                Map.of()
        );

        mockMvc.perform(post("/api/v1/kyc/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "test-tenant")
                        .content(asJsonString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @WithMockUser(authorities = "SCOPE_kyc:read")
    void shouldReturn404ForNonExistentCase() throws Exception {
        mockMvc.perform(get("/api/v1/kyc/non-existent-id/status")
                        .header("X-Tenant-Id", "test-tenant"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldReturn401WithoutAuth() throws Exception {
        mockMvc.perform(post("/api/v1/kyc/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "test-tenant")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private static String asJsonString(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
