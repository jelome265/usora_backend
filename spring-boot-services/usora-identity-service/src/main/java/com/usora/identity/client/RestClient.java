package com.usora.identity.client;

import com.usora.identity.config.TenantConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RestClient {

    private final TenantConfig tenantConfig;
    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) tenantConfig.getOpa().getTimeoutMs());
        factory.setReadTimeout((int) tenantConfig.getOpa().getTimeoutMs());
        restTemplate = new RestTemplate(factory);
    }

    public Map<String, Object> post(String url, Map<String, Object> body) {
        try {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            var entity = new HttpEntity<>(body, headers);
            var response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getBody() != null) {
                return response.getBody();
            }
            log.warn("Empty response from POST {}", url);
            return Map.of();
        } catch (Exception e) {
            log.error("REST POST failed for URL {}: {}", url, e.getMessage());
            throw e;
        }
    }

    public Map<String, Object> get(String url) {
        try {
            var response = restTemplate.getForEntity(url, Map.class);
            if (response.getBody() != null) {
                return response.getBody();
            }
            return Map.of();
        } catch (Exception e) {
            log.error("REST GET failed for URL {}: {}", url, e.getMessage());
            throw e;
        }
    }
}
