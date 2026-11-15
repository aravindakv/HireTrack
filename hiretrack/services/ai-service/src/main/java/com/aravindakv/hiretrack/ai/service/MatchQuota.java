package com.aravindakv.hiretrack.ai.service;

import com.aravindakv.hiretrack.ai.config.AiProperties;
import com.aravindakv.hiretrack.common.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Spec 3.3: 20 matches per user per UTC day. Failed calls give the slot back. */
@Component
public class MatchQuota {

    private static final Duration KEY_TTL = Duration.ofHours(26);   // outlives its UTC day

    /** INCR and set the TTL on the first increment, in one atomic step, so a crash can't leave a key without expiry. */
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return n""", Long.class);

    private final StringRedisTemplate redis;
    private final AiProperties props;
    private final Clock clock;

    public MatchQuota(StringRedisTemplate redis, AiProperties props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    public void take(long userId) {
        String key = key(userId);
        Long used = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(KEY_TTL.toSeconds()));
        if (used != null && used > props.dailyMatchQuota()) {
            redis.opsForValue().decrement(key);
            throw ApiException.tooManyRequests("MATCH_QUOTA_EXCEEDED",
                    "You've used today's " + props.dailyMatchQuota() + " matches. Try again tomorrow.", 3600);
        }
    }

    public void giveBack(long userId) {
        redis.opsForValue().decrement(key(userId));
    }

    private String key(long userId) {
        return "ai_quota:" + userId + ":" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}