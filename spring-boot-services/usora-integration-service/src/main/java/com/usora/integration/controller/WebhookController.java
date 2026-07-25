package com.usora.integration.controller;

import com.usora.integration.dto.RequestDto;
import com.usora.integration.dto.ResponseDto;
import com.usora.integration.service.DomainService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final DomainService domainService;

    public WebhookController(DomainService domainService) {
        this.domainService = domainService;
    }

    @PostMapping(value = "/webhooks/{tenantId}/{integrationId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto.WebhookIngestResponse> ingestWebhook(
            @PathVariable String tenantId,
            @PathVariable String integrationId,
            @Valid @RequestBody RequestDto.WebhookIngestRequest request) {
        log.info("Webhook ingest: tenant={}, integration={}, eventType={}",
                tenantId, integrationId, request.getEventType());
        ResponseDto.WebhookIngestResponse response = domainService.ingestWebhook(tenantId, integrationId, request);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/webhooks/{tenantId}/{integrationId}/health")
    public ResponseEntity<Map<String, Object>> getWebhookHealth(
            @PathVariable String tenantId,
            @PathVariable String integrationId) {
        Map<String, Object> health = domainService.getWebhookHealth(tenantId, integrationId);
        return ResponseEntity.ok(health);
    }
}
