package com.usora.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import com.usora.integration.util.EgressUrlGuard;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class RestClient {

    private static final Logger log = LoggerFactory.getLogger(RestClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public RestClient(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Timed(value = "integration.rest.post", description = "Time spent executing REST POST", percentiles = {0.5, 0.95, 0.99})
    @CircuitBreaker(name = "restClient", fallbackMethod = "postFallback")
    @Retry(name = "restClient")
    @TimeLimiter(name = "restClient")
    public CompletableFuture<JsonNode> post(String url, Object body, Map<String, String> headers) {
        // SECURITY: re-validated on every call (not just when the URL/webhook
        // config was saved) so a DNS-rebinding attack — where the hostname
        // resolved to a safe address at config time but an internal address
        // at request time — is also caught. See EgressUrlGuard for details.
        EgressUrlGuard.assertSafeDestination(url);
        return webClient.post()
                .uri(url)
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    if (headers != null) {
                        headers.forEach(h::set);
                    }
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .toFuture();
    }

    @Timed(value = "integration.rest.get", description = "Time spent executing REST GET", percentiles = {0.5, 0.95, 0.99})
    @CircuitBreaker(name = "restClient", fallbackMethod = "getFallback")
    @Retry(name = "restClient")
    @TimeLimiter(name = "restClient")
    public CompletableFuture<JsonNode> get(String url, Map<String, String> headers) {
        EgressUrlGuard.assertSafeDestination(url);
        return webClient.get()
                .uri(url)
                .headers(h -> {
                    if (headers != null) {
                        headers.forEach(h::set);
                    }
                })
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .toFuture();
    }

    @Timed(value = "integration.rest.put", description = "Time spent executing REST PUT", percentiles = {0.5, 0.95, 0.99})
    @CircuitBreaker(name = "restClient", fallbackMethod = "putFallback")
    @Retry(name = "restClient")
    @TimeLimiter(name = "restClient")
    public CompletableFuture<JsonNode> put(String url, Object body, Map<String, String> headers) {
        EgressUrlGuard.assertSafeDestination(url);
        return webClient.put()
                .uri(url)
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    if (headers != null) {
                        headers.forEach(h::set);
                    }
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .toFuture();
    }

    public CompletableFuture<JsonNode> postFallback(String url, Object body, Map<String, String> headers, Throwable t) {
        log.warn("REST POST fallback for {}: {}", url, t.getMessage());
        return CompletableFuture.failedFuture(t);
    }

    public CompletableFuture<JsonNode> getFallback(String url, Map<String, String> headers, Throwable t) {
        log.warn("REST GET fallback for {}: {}", url, t.getMessage());
        return CompletableFuture.failedFuture(t);
    }

    public CompletableFuture<JsonNode> putFallback(String url, Object body, Map<String, String> headers, Throwable t) {
        log.warn("REST PUT fallback for {}: {}", url, t.getMessage());
        return CompletableFuture.failedFuture(t);
    }
}
