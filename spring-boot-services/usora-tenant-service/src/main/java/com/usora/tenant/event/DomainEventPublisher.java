package com.usora.tenant.event;

import com.usora.tenant.entity.TenantEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DomainEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishTenantProvisioned(TenantEntity tenant) {
        TenantProvisionedEvent event = new TenantProvisionedEvent(
                tenant.getId(),
                tenant.getName(),
                tenant.getDomain(),
                tenant.getPlan(),
                tenant.getRegion(),
                Instant.now()
        );
        publish("tenant-provisioned", tenant.getId().toString(), event);
    }

    public void publishTenantSuspended(TenantEntity tenant, String reason) {
        TenantSuspendedEvent event = new TenantSuspendedEvent(
                tenant.getId(),
                tenant.getName(),
                tenant.getDomain(),
                reason,
                Instant.now()
        );
        publish("tenant-suspended", tenant.getId().toString(), event);
    }

    public void publishTenantResumed(TenantEntity tenant) {
        TenantResumedEvent event = new TenantResumedEvent(
                tenant.getId(),
                tenant.getName(),
                tenant.getDomain(),
                Instant.now()
        );
        publish("tenant-resumed", tenant.getId().toString(), event);
    }

    public void publishTenantOffboarded(TenantEntity tenant) {
        TenantOffboardedEvent event = new TenantOffboardedEvent(
                tenant.getId(),
                tenant.getName(),
                tenant.getDomain(),
                Instant.now()
        );
        publish("tenant-offboarded", tenant.getId().toString(), event);
    }

    public void publishTenantConfigUpdated(TenantEntity tenant, Map<String, Object> oldConfig, Map<String, Object> newConfig) {
        TenantConfigUpdatedEvent event = new TenantConfigUpdatedEvent(
                tenant.getId(),
                tenant.getName(),
                oldConfig,
                newConfig,
                Instant.now()
        );
        publish("tenant-config-updated", tenant.getId().toString(), event);
    }

    private void publish(String topic, String key, Object event) {
        CompletableFuture<Void> future = kafkaTemplate.send(topic, key, event)
                .thenAccept(result -> log.debug("Published event to topic {}: key={}", topic, key))
                . exceptionally(ex -> {
                    log.error("Failed to publish event to topic {}: {}", topic, ex.getMessage());
                    return null;
                });
    }

    public record TenantProvisionedEvent(UUID tenantId, String name, String domain,
                                          String plan, String region, Instant timestamp) {}
    public record TenantSuspendedEvent(UUID tenantId, String name, String domain,
                                        String reason, Instant timestamp) {}
    public record TenantResumedEvent(UUID tenantId, String name, String domain,
                                      Instant timestamp) {}
    public record TenantOffboardedEvent(UUID tenantId, String name, String domain,
                                         Instant timestamp) {}
    public record TenantConfigUpdatedEvent(UUID tenantId, String name,
                                            Map<String, Object> oldConfig,
                                            Map<String, Object> newConfig,
                                            Instant timestamp) {}
}
