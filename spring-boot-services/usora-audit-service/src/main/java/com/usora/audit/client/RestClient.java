package com.usora.audit.client;

import com.usora.audit.entity.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@Qualifier("siemRestClient")
public class RestClient {

    private static final Logger log = LoggerFactory.getLogger(RestClient.class);

    private final RestTemplate restTemplate;
    private final String siemUrl;
    private final String siemToken;

    public RestClient(@Value("${audit.siem.url:http://localhost:8088}") String siemUrl,
                      @Value("${audit.siem.token:}") String siemToken) {
        this.restTemplate = new RestTemplate();
        this.siemUrl = siemUrl;
        this.siemToken = siemToken;
    }

    public void sendToSiem(AuditEvent event) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!siemToken.isEmpty()) {
                headers.setBearerAuth(siemToken);
            }
            HttpEntity<AuditEvent> request = new HttpEntity<>(event, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    siemUrl + "/services/collector/event", request, String.class);
            log.debug("SIEM event sent: {}, response: {}", event.getId(), response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to send event to SIEM: {}", e.getMessage());
        }
    }

    public void sendAlert(String alertPayload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!siemToken.isEmpty()) {
                headers.setBearerAuth(siemToken);
            }
            HttpEntity<String> request = new HttpEntity<>(alertPayload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    siemUrl + "/services/collector/alert", request, String.class);
            log.info("SIEM alert sent: {}", response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to send alert to SIEM: {}", e.getMessage());
        }
    }
}
