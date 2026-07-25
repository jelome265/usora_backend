package com.usora.integration.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import io.cloudevents.kafka.CloudEventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final KafkaTemplate<String, CloudEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${integration.kafka.topic.webhook-events:webhook-events}")
    private String webhookEventsTopic;

    @Value("${integration.kafka.topic.banking-events:banking-events}")
    private String bankingEventsTopic;

    @Value("${integration.kafka.topic.government-events:government-events}")
    private String governmentEventsTopic;

    @Value("${integration.kafka.topic.credit-events:credit-events}")
    private String creditEventsTopic;

    @Value("${integration.service.name:usora-integration-service}")
    private String serviceName;

    static {
        EventFormatProvider.getInstance().registerFormat(JsonFormat.CONTENT_TYPE, new JsonFormat());
    }

    public DomainEventPublisher(@Qualifier("cloudEventKafkaTemplate") KafkaTemplate<String, CloudEvent> kafkaTemplate,
                                ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<SendResult<String, CloudEvent>> publishWebhookEvent(
            String tenantId, String integrationId, String idempotencyKey,
            Object originalPayload, Map<String, Object> normalizedData, Map<String, String> metadata) {

        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("integration/" + serviceName + "/webhooks/" + tenantId + "/" + integrationId))
                .withType("com.usora.integration.webhook.ingested.v1")
                .withSubject(idempotencyKey)
                .withTime(OffsetDateTime.now())
                .withDataContentType("application/json")
                .withExtension("tenantid", tenantId)
                .withExtension("integrationid", integrationId)
                .withExtension("idempotencykey", idempotencyKey)
                .withData(writeJsonBytes(Map.of(
                        "originalPayload", originalPayload,
                        "normalizedData", normalizedData,
                        "metadata", metadata
                )))
                .build();

        return publishEvent(webhookEventsTopic, tenantId + ":" + integrationId, event);
    }

    public CompletableFuture<SendResult<String, CloudEvent>> publishBankingEvent(
            String tenantId, String userId, String eventType, Map<String, Object> data) {

        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("integration/" + serviceName + "/banking/" + tenantId))
                .withType("com.usora.integration.banking." + eventType + ".v1")
                .withTime(OffsetDateTime.now())
                .withDataContentType("application/json")
                .withExtension("tenantid", tenantId)
                .withExtension("userid", userId)
                .withData(writeJsonBytes(data))
                .build();

        return publishEvent(bankingEventsTopic, tenantId + ":" + userId, event);
    }

    public CompletableFuture<SendResult<String, CloudEvent>> publishGovernmentEvent(
            String tenantId, String userId, String eventType, Map<String, Object> data) {

        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("integration/" + serviceName + "/government/" + tenantId))
                .withType("com.usora.integration.government." + eventType + ".v1")
                .withTime(OffsetDateTime.now())
                .withDataContentType("application/json")
                .withExtension("tenantid", tenantId)
                .withExtension("userid", userId)
                .withData(writeJsonBytes(data))
                .build();

        return publishEvent(governmentEventsTopic, tenantId + ":" + userId, event);
    }

    public CompletableFuture<SendResult<String, CloudEvent>> publishCreditEvent(
            String tenantId, String userId, String eventType, Map<String, Object> data) {

        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("integration/" + serviceName + "/credit/" + tenantId))
                .withType("com.usora.integration.credit." + eventType + ".v1")
                .withTime(OffsetDateTime.now())
                .withDataContentType("application/json")
                .withExtension("tenantid", tenantId)
                .withExtension("userid", userId)
                .withData(writeJsonBytes(data))
                .build();

        return publishEvent(creditEventsTopic, tenantId + ":" + userId, event);
    }

    private CompletableFuture<SendResult<String, CloudEvent>> publishEvent(
            String topic, String key, CloudEvent event) {
        return kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event to topic {}: {}", topic, ex.getMessage());
                    } else {
                        log.debug("Published event {} to topic {} partition {} offset {}",
                                event.getId(), topic, result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    private byte[] writeJsonBytes(Object data) {
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.error("Failed to serialize event data", e);
            return new byte[0];
        }
    }
}
