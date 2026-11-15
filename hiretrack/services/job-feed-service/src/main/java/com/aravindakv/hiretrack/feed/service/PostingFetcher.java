package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.feed.client.AdzunaClient;
import com.aravindakv.hiretrack.feed.client.AdzunaClient.AdzunaPosting;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.domain.SearchProfile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Adzuna fetch per query key per hour, shared by every user with the same search.
 * A short Redis lock stops a thundering herd: other workers wait for the cached result.
 */
@Component
public class PostingFetcher {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final AdzunaClient adzuna;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final FeedProperties.Feed props;
    private final MeterRegistry meters;
    private final Clock clock;

    public PostingFetcher(AdzunaClient adzuna, StringRedisTemplate redis, ObjectMapper json, FeedProperties props,
                          MeterRegistry meters, Clock clock) {
        this.adzuna = adzuna;
        this.redis = redis;
        this.json = json;
        this.props = props.feed();
        this.meters = meters;
        this.clock = clock;
    }

    public List<AdzunaPosting> fetch(SearchProfile p) {
        String cacheKey = "adzuna:" + p.getQueryKey() + ":" + HOUR.format(clock.instant());
        String lockKey = cacheKey + ":lock";
        for (int i = 0; i < 40; i++) {                            // up to ~10 s waiting for another worker
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                meters.counter("hiretrack.feed.query.cache.hits", "result", "hit").increment();
                return read(cached);
            }
            Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
            if (Boolean.TRUE.equals(locked)) {
                try {
                    meters.counter("hiretrack.feed.query.cache.hits", "result", "miss").increment();
                    List<AdzunaPosting> fresh = fetchFromAdzuna(p);
                    redis.opsForValue().set(cacheKey, write(fresh), props.queryCacheTtl());
                    return fresh;
                } finally {
                    redis.delete(lockKey);
                }
            }
            sleep(250);
        }
        return fetchFromAdzuna(p);                                // lock holder died: fetch ourselves
    }

    private List<AdzunaPosting> fetchFromAdzuna(SearchProfile p) {
        Map<String, AdzunaPosting> byId = new LinkedHashMap<>();
        adzuna.search(p.getCountry(), p.getKeywords(), p.getLocation(), p.getRadiusKm(),
                props.maxDaysOld(), props.maxResults()).forEach(a -> byId.put(a.externalId(), a));
        if (p.isRemoteOk()) {
            adzuna.search(p.getCountry(), p.getKeywords() + " remote", null, null,
                    props.maxDaysOld(), props.maxRemoteResults()).forEach(a -> byId.putIfAbsent(a.externalId(), a));
        }
        meters.counter("hiretrack.feed.postings", "stage", "fetched").increment(byId.size());
        return new ArrayList<>(byId.values());
    }

    private String write(List<AdzunaPosting> postings) {
        try { return json.writeValueAsString(postings); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private List<AdzunaPosting> read(String s) {
        try { return json.readValue(s, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}