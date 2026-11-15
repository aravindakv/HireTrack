package com.aravindakv.hiretrack.ai.config;

import com.aravindakv.hiretrack.ai.provider.AiUpstreamException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    @Bean
    BulkheadRegistry bulkheadRegistry(AiProperties props) {
        return BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(props.resilience().maxConcurrentMatches())
                .maxWaitDuration(Duration.ZERO)                 // full → 429 immediately, never queue
                .build());
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(AiProperties props) {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(props.resilience().breakerWindow())
                .minimumNumberOfCalls(Math.max(1, props.resilience().breakerWindow() / 2))
                .failureRateThreshold(props.resilience().breakerFailureRate())
                .waitDurationInOpenState(props.resilience().breakerOpenFor())
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(AiUpstreamException.class)    // refusals and bad JSON don't open the circuit
                .build());
    }
}