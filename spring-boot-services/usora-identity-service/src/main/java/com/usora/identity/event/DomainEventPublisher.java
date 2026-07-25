package com.usora.identity.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Async("authEventExecutor")
    public void publishAuthEvent(String type, Map<String, Object> details) {
        try {
            var event = Map.<String, Object>of(
                    "event_id", UUID.randomUUID().toString(),
                    "event_type", type,
                    "timestamp", Instant.now().toString(),
                    "service", "usora-identity-service",
                    "details", details
            );
            var payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send("security.audit", type, payload);
            log.debug("Published auth event: {} - {}", type, payload);
        } catch (Exception e) {
            log.error("Failed to publish auth event: {}", type, e);
        }
    }

    @Async("authEventExecutor")
    public void publishTokenEvent(String type, String tokenId, String tenantId, String clientId) {
        publishAuthEvent(type, Map.of(
                "token_id", tokenId,
                "tenant_id", tenantId,
                "client_id", clientId
        ));
    }

    @Async("authEventExecutor")
    public void publishUserEvent(String type, String userId, String tenantId, Map<String, Object> changes) {
        var details = new java.util.HashMap<String, Object>();
        details.put("user_id", userId);
        details.put("tenant_id", tenantId);
        if (changes != null) {
            details.put("changes", changes);
        }
        publishAuthEvent(type, details);
    }
}
