package com.usora.compliance.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class RestClient {

    private static final Logger log = LoggerFactory.getLogger(RestClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public RestClient(ObjectMapper objectMapper,
                      @Value("${compliance.client.timeout-seconds:30}") int timeoutSeconds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public String fetchSanctionsList(String url) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("Successfully fetched sanctions list from {}", url);
                return response.body();
            } else {
                log.warn("Failed to fetch sanctions list from {}: HTTP {}", url, response.statusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("Error fetching sanctions list from {}: {}", url, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> post(String url, Map<String, Object> body) {
        try {
            var json = objectMapper.writeValueAsString(body);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), Map.class);
            } else {
                log.warn("POST to {} returned HTTP {}", url, response.statusCode());
                return Map.of("error", "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("Error posting to {}: {}", url, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
}
