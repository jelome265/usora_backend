package com.usora.identity.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_TOKEN = "token";
    public static final String CACHE_SESSION = "session";
    public static final String CACHE_POLICY_DECISION = "policyDecision";
    public static final String CACHE_BRUTE_FORCE = "bruteForce";
    public static final String CACHE_TENANT_KEYS = "tenantKeys";

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configs = Map.of(
                CACHE_TOKEN, defaults.entryTtl(Duration.ofMinutes(15)),
                CACHE_SESSION, defaults.entryTtl(Duration.ofHours(24)),
                CACHE_POLICY_DECISION, defaults.entryTtl(Duration.ofSeconds(60)),
                CACHE_BRUTE_FORCE, defaults.entryTtl(Duration.ofMinutes(5)),
                CACHE_TENANT_KEYS, defaults.entryTtl(Duration.ofHours(1))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(configs)
                .build();
    }
}
