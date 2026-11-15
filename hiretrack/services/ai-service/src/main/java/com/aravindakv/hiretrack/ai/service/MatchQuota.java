package com.aravindakv.hiretrack.ai.service;

import com.aravindakv.hiretrack.ai.config.AiProperties;
import com.aravindakv.hiretrack.common.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Spec 3.3: 20 matches per user per UTC day. Failed calls give the slot back. */
@Component
public class MatchQuota {

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
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1) redis.expire(key, Duration.ofHours(26));
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