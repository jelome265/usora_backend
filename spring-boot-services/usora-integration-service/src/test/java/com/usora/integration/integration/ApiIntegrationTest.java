package com.usora.integration.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.integration.Application;
import com.usora.integration.dto.RequestDto;
import com.usora.integration.entity.WebhookConfig;
import com.usora.integration.repository.TenantRepository.WebhookConfigRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"test-webhook-events"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebhookConfigRepository webhookConfigRepository;

    @BeforeEach
    void setUp() {
        webhookConfigRepository.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("Health endpoint should return UP")
    void healthCheck() throws Exception {
        mockMvc.perform(post("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(2)
    @DisplayName("POST /webhooks/{tenantId}/{integrationId} should return 202")
    void ingestWebhook() throws Exception {
        WebhookConfig config = new WebhookConfig();
        config.setTenantId("test-tenant");
        config.setEndpointId("test-integration");
        config.setUrl("https://example.com/webhook");
        config.setEnabled(true);
        config.setStatus(WebhookConfig.WebhookStatus.ACTIVE);
        config.setAuthType(WebhookConfig.AuthType.NONE);
        config.setEvents(Set.of("test.event"));
        config.setSecret("test-secret");
        webhookConfigRepository.save(config);

        RequestDto.WebhookIngestRequest request = RequestDto.WebhookIngestRequest.builder()
                .eventType("test.event")
                .idempotencyKey("test-idempotency-1")
                .payload(Map.of("key", "value"))
                .build();

        mockMvc.perform(post("/webhooks/test-tenant/test-integration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.idempotencyKey").value("test-idempotency-1"));
    }
}
