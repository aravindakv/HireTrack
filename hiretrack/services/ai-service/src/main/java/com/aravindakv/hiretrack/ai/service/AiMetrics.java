package com.aravindakv.hiretrack.ai.service;

import com.aravindakv.hiretrack.ai.model.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/** Spec 10.5 AI metrics. */
@Component
public class AiMetrics {

    private final MeterRegistry registry;

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(String provider, String operation, String model, String outcome) {
        Counter.builder("hiretrack.ai.requests")
                .tags("provider", provider, "operation", operation, "model", model, "outcome", outcome)
                .register(registry).increment();
    }

    public void latency(String provider, String operation, String model, Duration elapsed) {
        Timer.builder("hiretrack.ai.latency")
                .tags("provider", provider, "operation", operation, "model", model)
                .publishPercentileHistogram()
                .register(registry).record(elapsed);
    }

    public void usage(String provider, String operation, TokenUsage u, BigDecimal costUsd) {
        tokens(provider, u.model(), "input", u.inputTokens() - u.cachedInputTokens());
        tokens(provider, u.model(), "cache_read", u.cachedInputTokens());
        tokens(provider, u.model(), "output", u.outputTokens());
        Counter.builder("hiretrack.ai.cost.usd")
                .tags("provider", provider, "model", u.model(), "operation", operation)
                .register(registry).increment(costUsd.doubleValue());
    }

    private void tokens(String provider, String model, String type, long n) {
        Counter.builder("hiretrack.ai.tokens").tags("provider", provider, "model", model, "type", type)
                .register(registry).increment(Math.max(0, n));
    }
}