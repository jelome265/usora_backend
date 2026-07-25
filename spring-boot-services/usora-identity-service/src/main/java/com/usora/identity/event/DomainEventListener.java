package com.usora.identity.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventListener {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "identity.events", groupId = "usora-identity-group")
    public void handleIdentityEvent(String message) {
        try {
            var event = objectMapper.readValue(message, Map.class);
            log.debug("Received identity event: {}", event.get("event_type"));
        } catch (Exception e) {
            log.error("Failed to process identity event", e);
        }
    }

    @KafkaListener(topics = "tenant.events", groupId = "usora-identity-group")
    public void handleTenantEvent(String message) {
        try {
            var event = objectMapper.readValue(message, Map.class);
            var eventType = (String) event.get("event_type");
            log.info("Processing tenant event: {}", eventType);

            if ("tenant.provisioned".equals(eventType)) {
                handleTenantProvisioned(event);
            } else if ("tenant.deactivated".equals(eventType)) {
                handleTenantDeactivated(event);
            }
        } catch (Exception e) {
            log.error("Failed to process tenant event", e);
        }
    }

    private void handleTenantProvisioned(Map<String, Object> event) {
        log.info("New tenant provisioned: {}", event.get("tenant_id"));
    }

    private void handleTenantDeactivated(Map<String, Object> event) {
        log.info("Tenant deactivated: {}", event.get("tenant_id"));
    }
}
