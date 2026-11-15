package com.aravindakv.hiretrack.feed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("hiretrack")
public record FeedProperties(Feed feed, Adzuna adzuna) {

    public record Feed(boolean schedulersEnabled, int runHour, int jitterMinutes, int activeDays, int maxResults,
                       int maxRemoteResults, int maxDaysOld, int topN, Duration manualRefreshCooldown,
                       int submitBatchSize, int maxScoreAttempts, Duration queryCacheTtl, int retentionDays) {}

    public record Adzuna(String baseUrl, String appId, String appKey, Duration timeout) {}
}