package com.eventrelay.api.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * A best-effort Redis cache mapping {@code (tenant, idempotency-key)} to the
 * event id it first produced. It short-circuits repeat ingests of the same key
 * before they reach the database. The database unique constraint remains the
 * source of truth, so Redis being unavailable only costs the fast path — it
 * never affects correctness.
 */
@Component
public class RedisIdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyCache.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisIdempotencyCache(StringRedisTemplate redis,
                                 @Value("${eventrelay.idempotency.ttl-hours:24}") long ttlHours) {
        this.redis = redis;
        this.ttl = Duration.ofHours(ttlHours);
    }

    /** Returns the previously stored event id for this key, if cached. */
    public Optional<UUID> lookup(UUID tenantId, String key) {
        try {
            String cached = redis.opsForValue().get(redisKey(tenantId, key));
            return Optional.ofNullable(cached).map(UUID::fromString);
        } catch (Exception e) {
            log.warn("Redis idempotency lookup failed (degrading to DB): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Records the event id produced for this key, with a bounded TTL. */
    public void remember(UUID tenantId, String key, UUID eventId) {
        try {
            redis.opsForValue().set(redisKey(tenantId, key), eventId.toString(), ttl);
        } catch (Exception e) {
            log.warn("Redis idempotency write failed (ignored): {}", e.getMessage());
        }
    }

    private String redisKey(UUID tenantId, String key) {
        return "idem:" + tenantId + ":" + key;
    }
}
