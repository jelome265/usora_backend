package com.usora.compliance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        var objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .deactivateDefaultTyping();

        var serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .prefixCacheNameWith("compliance:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("rules", defaultConfig.entryTtl(Duration.ofMinutes(15)))
                .withCacheConfiguration("sanctions", defaultConfig.entryTtl(Duration.ofMinutes(30)))
                .withCacheConfiguration("jurisdictions", defaultConfig.entryTtl(Duration.ofHours(2)))
                .withCacheConfiguration("evidence", defaultConfig.entryTtl(Duration.ofMinutes(5)))
                .build();
    }
}
