package com.usora.integration.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.CloudEventDeserializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private final ObjectMapper objectMapper;
    private final Map<String, Integer> retryCounters = new ConcurrentHashMap<>();

    public DomainEventListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${integration.kafka.topic.webhook-results:webhook-results}",
            groupId = "${spring.kafka.consumer.group-id:integration-service}",
            containerFactory = "cloudEventKafkaListenerContainerFactory"
    )
    public void onWebhookResult(@Payload ConsumerRecord<String, CloudEvent> record,
                                @Header("kafka_offset") long offset,
                                Acknowledgment ack) {
        try {
            CloudEvent event = record.value();
            log.info("Received webhook result event: id={}, type={}, source={}",
                    event.getId(), event.getType(), event.getSource());

            String tenantId = event.getExtension("tenantid") != null
                    ? event.getExtension("tenantid").toString() : null;
            String integrationId = event.getExtension("integrationid") != null
                    ? event.getExtension("integrationid").toString() : null;

            log.info("Webhook result for tenant={}, integration={}: success",
                    tenantId, integrationId);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing webhook result event", e);
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = "${integration.kafka.topic.banking-events:banking-events}",
            groupId = "${spring.kafka.consumer.group-id:integration-service}",
            containerFactory = "cloudEventKafkaListenerContainerFactory"
    )
    public void onBankingEvent(@Payload ConsumerRecord<String, CloudEvent> record, Acknowledgment ack) {
        try {
            CloudEvent event = record.value();
            log.debug("Received banking event: id={}, type={}", event.getId(), event.getType());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing banking event", e);
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = "${integration.kafka.topic.government-events:government-events}",
            groupId = "${spring.kafka.consumer.group-id:integration-service}",
            containerFactory = "cloudEventKafkaListenerContainerFactory"
    )
    public void onGovernmentEvent(@Payload ConsumerRecord<String, CloudEvent> record, Acknowledgment ack) {
        try {
            CloudEvent event = record.value();
            log.debug("Received government event: id={}, type={}", event.getId(), event.getType());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing government event", e);
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = "${integration.kafka.topic.credit-events:credit-events}",
            groupId = "${spring.kafka.consumer.group-id:integration-service}",
            containerFactory = "cloudEventKafkaListenerContainerFactory"
    )
    public void onCreditEvent(@Payload ConsumerRecord<String, CloudEvent> record, Acknowledgment ack) {
        try {
            CloudEvent event = record.value();
            log.debug("Received credit event: id={}, type={}", event.getId(), event.getType());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing credit event", e);
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = "${integration.kafka.topic.dead-letter:webhook-dead-letter}",
            groupId = "${spring.kafka.consumer.group-id:integration-service}",
            containerFactory = "cloudEventKafkaListenerContainerFactory"
    )
    public void onDeadLetterEvent(@Payload ConsumerRecord<String, CloudEvent> record, Acknowledgment ack) {
        try {
            CloudEvent event = record.value();
            log.warn("Dead letter event: id={}, type={}, source={}",
                    event.getId(), event.getType(), event.getSource());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing dead letter event", e);
            ack.acknowledge();
        }
    }
}
