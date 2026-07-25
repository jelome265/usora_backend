package com.usora.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class CacheConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${integration.cache.webhook.ttl:86400}")
    private long webhookCacheTtl;

    @Value("${integration.cache.banking.ttl:3600}")
    private long bankingCacheTtl;

    @Value("${integration.cache.government.ttl:1800}")
    private long governmentCacheTtl;

    @Value("${integration.cache.credit.ttl:3600}")
    private long creditCacheTtl;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (!redisPassword.isBlank()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);
        if (!redisPassword.isBlank()) {
            serverConfig.setPassword(redisPassword);
        }
        serverConfig.setConnectionPoolSize(10)
                .setConnectionMinimumIdleSize(5)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);
        return Redisson.create(config);
    }

    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.deactivateDefaultTyping();
        return mapper;
    }

    private RedisCacheConfiguration baseCacheConfig(ObjectMapper mapper) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer(mapper)))
                .disableCachingNullValues();
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper mapper) {
        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration("webhookIdempotency",
                        baseCacheConfig(mapper).entryTtl(Duration.ofSeconds(webhookCacheTtl)))
                .withCacheConfiguration("bankingLinks",
                        baseCacheConfig(mapper).entryTtl(Duration.ofSeconds(bankingCacheTtl)))
                .withCacheConfiguration("governmentVerifications",
                        baseCacheConfig(mapper).entryTtl(Duration.ofSeconds(governmentCacheTtl)))
                .withCacheConfiguration("creditReports",
                        baseCacheConfig(mapper).entryTtl(Duration.ofSeconds(creditCacheTtl)))
                .withCacheConfiguration("integrationProviders",
                        baseCacheConfig(mapper).entryTtl(Duration.ofMinutes(15)))
                .build();
    }

    @Bean("tenantAwareKeyGenerator")
    public KeyGenerator tenantAwareKeyGenerator() {
        return (target, method, params) -> {
            StringBuilder sb = new StringBuilder();
            sb.append(target.getClass().getSimpleName()).append("::");
            sb.append(method.getName()).append("::");
            for (Object param : params) {
                sb.append(param != null ? param.toString() : "null").append("::");
            }
            return sb.toString();
        };
    }
}
