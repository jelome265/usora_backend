package com.usora.core.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                .build();
    }

    public Mono<String> post(String uri, Object body) {
        return webClient.post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .doOnError(e -> log.error("HTTP POST {} failed: {}", uri, e.getMessage()));
    }

    public Mono<String> get(String uri) {
        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .doOnError(e -> log.error("HTTP GET {} failed: {}", uri, e.getMessage()));
    }

    public Mono<String> postWithHeaders(String uri, Object body, Map<String, String> headers) {
        return webClient.post()
                .uri(uri)
                .headers(h -> headers.forEach(h::set))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10));
    }
}
