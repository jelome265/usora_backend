package com.usora.identity.job;

import com.usora.identity.config.TenantConfig;
import com.usora.identity.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledCleanupJob {

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;
    private final TenantConfig tenantConfig;

    @Scheduled(fixedDelayString = "${identity.cleanup.interval:300000}")
    public void cleanupExpiredTokens() {
        log.debug("Starting expired token cleanup");
        try {
            Set<String> keys = redisTemplate.keys("token:*:refresh:*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            int removed = 0;
            for (String key : keys) {
                var ttl = redisTemplate.getExpire(key);
                if (ttl != null && ttl <= 0) {
                    redisTemplate.delete(key);
                    removed++;
                }
            }
            log.info("Cleaned up {} expired refresh tokens", removed);
        } catch (Exception e) {
            log.error("Failed to cleanup expired tokens", e);
        }
    }

    @Scheduled(cron = "${identity.cleanup.brute-force-cron:0 */5 * * * *}")
    public void cleanupStaleBruteForceCounters() {
        log.debug("Starting brute force counter cleanup");
        try {
            Set<String> keys = redisTemplate.keys("bruteforce:*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            int removed = 0;
            for (String key : keys) {
                var ttl = redisTemplate.getExpire(key);
                if (ttl != null && ttl <= 0) {
                    redisTemplate.delete(key);
                    removed++;
                }
            }
            log.info("Cleaned up {} stale brute force counters", removed);
        } catch (Exception e) {
            log.error("Failed to cleanup brute force counters", e);
        }
    }

    @Scheduled(cron = "${identity.cleanup.key-rotation-cron:0 0 3 * * ?}")
    public void rotateKeys() {
        log.info("Starting scheduled key rotation");
        try {
            jwtTokenProvider.rotateKeys();
        } catch (Exception e) {
            log.error("Failed to rotate keys", e);
        }
    }
}
