package com.aravindakv.hiretrack.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hiretrack.outbox")
public record OutboxProperties(Boolean enabled, Long relayDelayMs, Integer batchSize, Integer retentionDays) {
    public OutboxProperties {
        if (enabled == null) enabled = true;
        if (relayDelayMs == null) relayDelayMs = 500L;
        if (batchSize == null) batchSize = 100;
        if (retentionDays == null) retentionDays = 7;
    }
}