package com.usora.compliance.integration;

import com.usora.compliance.dto.ComplianceValidationRequest;
import com.usora.compliance.dto.RegulatoryRulesResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldRejectUnauthenticatedRequests() {
        var response = restTemplate.exchange(
                "/api/v1/compliance/rules",
                HttpMethod.GET,
                null,
                String.class);
        // Should be unauthorized (401 or 403 depending on security config)
        assertTrue(response.getStatusCode().is4xxClientError());
    }
}
