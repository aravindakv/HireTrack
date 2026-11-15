package com.aravindakv.hiretrack.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("hiretrack.ai")
public record AiProperties(
        String provider,
        Duration matchTimeout,
        int matchMaxRetries,
        Duration feedTimeout,
        int dailyMatchQuota,
        boolean batchPollEnabled,
        Duration fakeDelay,
        Provider openai,
        Provider anthropic,
        Map<String, Price> prices,
        Resilience resilience) {

    public record Resilience(int maxConcurrentMatches, int breakerWindow, float breakerFailureRate, Duration breakerOpenFor) {}

    public record Provider(String apiKey, String baseUrl, String matchModel, String feedModel) {}

    /** USD per million tokens; batchDiscount is a fraction (0.5 = 50% off). */
    public record Price(BigDecimal input, BigDecimal cachedInput, BigDecimal output, BigDecimal batchDiscount) {}

    public AiProperties {
        if (prices == null) prices = Map.of();
        if (resilience == null) resilience = new Resilience(32, 20, 50f, Duration.ofSeconds(30));
    }
}