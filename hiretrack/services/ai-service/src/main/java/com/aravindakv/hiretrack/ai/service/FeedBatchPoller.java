package com.aravindakv.hiretrack.ai.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hiretrack.ai.batch-poll-enabled", havingValue = "true")
public class FeedBatchPoller {

    private final FeedBatchService batches;

    public FeedBatchPoller(FeedBatchService batches) {
        this.batches = batches;
    }

    @Scheduled(fixedDelayString = "60s", initialDelayString = "20s")
    @SchedulerLock(name = "ai-batch-poller", lockAtMostFor = "5m", lockAtLeastFor = "30s")
    public void poll() {
        batches.pollOpenBatches();
    }
}