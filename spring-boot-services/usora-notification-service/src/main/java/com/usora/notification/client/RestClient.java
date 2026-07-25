package com.usora.notification.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executors;

@Slf4j
@Component
public class RestClient {

    @Configuration
    public static class RestClientConfig {

        @Bean
        public RestTemplate restTemplate() {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);
            factory.setReadTimeout(10000);
            return new RestTemplate(factory);
        }

        @Bean("fcmRestTemplate")
        public RestTemplate fcmRestTemplate() {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(5000);
            return new RestTemplate(factory);
        }
    }
}
