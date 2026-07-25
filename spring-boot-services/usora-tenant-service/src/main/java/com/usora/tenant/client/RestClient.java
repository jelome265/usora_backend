package com.usora.tenant.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class RestClient {

    private static final Logger log = LoggerFactory.getLogger(RestClient.class);

    private final WebClient webClient;

    public RestClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl("http://localhost:8080")
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public <T> T get(String uri, Class<T> responseType, Map<String, String> headers) {
        return webClient.get()
                .uri(uri)
                .headers(h -> headers.forEach(h::set))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(error -> Mono.error(new RuntimeException("GET failed: " + error))))
                .bodyToMono(responseType)
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    public <T> T post(String uri, Object body, Class<T> responseType, Map<String, String> headers) {
        return webClient.post()
                .uri(uri)
                .headers(h -> headers.forEach(h::set))
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(error -> Mono.error(new RuntimeException("POST failed: " + error))))
                .bodyToMono(responseType)
                .timeout(Duration.ofSeconds(30))
                .block();
    }

    public <T> T put(String uri, Object body, Class<T> responseType, Map<String, String> headers) {
        return webClient.put()
                .uri(uri)
                .headers(h -> headers.forEach(h::set))
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(error -> Mono.error(new RuntimeException("PUT failed: " + error))))
                .bodyToMono(responseType)
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    public void delete(String uri, Map<String, String> headers) {
        webClient.delete()
                .uri(uri)
                .headers(h -> headers.forEach(h::set))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(error -> Mono.error(new RuntimeException("DELETE failed: " + error))))
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(30))
                .block();
    }
}
