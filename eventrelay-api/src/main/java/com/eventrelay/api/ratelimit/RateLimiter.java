package com.eventrelay.api.ratelimit;

import com.eventrelay.core.domain.Tenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Per-tenant token-bucket rate limiter backed by an atomic Redis Lua script.
 * The bucket capacity/refill default from config and can be overridden per tenant
 * via {@code settings.rate_limit_rps}. Fails open if Redis is unavailable — rate
 * limiting is a protection, not a correctness guarantee.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisScript<List> script;
    private final int defaultRps;

    public RateLimiter(StringRedisTemplate redis, ObjectMapper objectMapper,
                       @Value("${eventrelay.rate-limit.default-rps:100}") int defaultRps) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.defaultRps = defaultRps;
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket.lua")));
        s.setResultType(List.class);
        this.script = s;
    }

    /** True if the request is within the tenant's rate limit. */
    public boolean allow(Tenant tenant) {
        int rps = resolveRps(tenant);
        long now = System.currentTimeMillis();
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(script,
                    List.of("ratelimit:" + tenant.getId()),
                    Integer.toString(rps),   // capacity
                    Integer.toString(rps),   // refill/sec
                    Long.toString(now),
                    "1");
            return result != null && !result.isEmpty() && result.get(0) == 1L;
        } catch (Exception e) {
            log.warn("Rate limiter unavailable (failing open): {}", e.getMessage());
            return true;
        }
    }

    private int resolveRps(Tenant tenant) {
        try {
            JsonNode settings = objectMapper.readTree(tenant.getSettings());
            JsonNode override = settings.get("rate_limit_rps");
            if (override != null && override.isInt() && override.asInt() > 0) {
                return override.asInt();
            }
        } catch (Exception ignored) {
            // fall back to default
        }
        return defaultRps;
    }
}
