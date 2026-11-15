package com.aravindakv.hiretrack.analytics.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** One Redis hash per user (field = timezone), so eviction is a single DEL without scanning keys. */
@Component
public class DashboardCache {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public DashboardCache(StringRedisTemplate redis, @Value("${hiretrack.dashboard.cache-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public Optional<String> get(long userId, String zone) {
        Object v = redis.opsForHash().get(key(userId), zone);
        return Optional.ofNullable((String) v);
    }

    public void put(long userId, String zone, String json) {
        redis.opsForHash().put(key(userId), zone, json);
        redis.expire(key(userId), ttl);
    }

    public void evict(long userId) {
        redis.delete(key(userId));
    }

    private static String key(long userId) {
        return "dashboard:" + userId;
    }
}