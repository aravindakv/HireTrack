package com.aravindakv.hiretrack.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hiretrack.idempotency")
public record IdempotencyProperties(Integer ttlHours, Integer lockSeconds) {
    public IdempotencyProperties {
        if (ttlHours == null) ttlHours = 24;
        if (lockSeconds == null) lockSeconds = 60;
    }
}