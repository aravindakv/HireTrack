package com.aravindakv.hiretrack.ai.provider.fake;

import com.aravindakv.hiretrack.ai.model.FeedScore;
import com.aravindakv.hiretrack.ai.model.FeedScoreRequest;
import com.aravindakv.hiretrack.ai.model.MatchResult;
import com.aravindakv.hiretrack.ai.model.TokenUsage;
import com.aravindakv.hiretrack.ai.provider.AiProvider;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Deterministic answers with a configurable delay. Never used with real users. */
public class FakeAiProvider implements AiProvider {

    private final Duration delay;

    public FakeAiProvider(Duration delay) {
        this.delay = delay;
    }

    @Override public String name() { return "fake"; }
    @Override public String feedModel() { return "fake-model"; }

    @Override
    public MatchCompletion match(String resumeText, String jobDescription) {
        sleep(delay);
        return new MatchCompletion(
                new MatchResult(72, List.of("Kubernetes"),
                        List.of("Built Java services", "Ran Kafka pipelines", "Tuned PostgreSQL"), "Acme", "Backend Engineer"),
                new TokenUsage("fake-model", 1000, 0, 200));
    }

    @Override
    public String submitFeedBatch(List<FeedScoreRequest> items) {
        return "fake-batch-" + UUID.randomUUID();
    }

    @Override
    public BatchPoll pollFeedBatch(String providerBatchId, List<FeedScoreRequest> submitted) {
        return new BatchPoll(BatchState.ENDED, submitted.stream().map(r -> new ItemResult(r.customId(), true,
                r.postings().stream()
                        .map(p -> new FeedScore(p.postingId(), 50 + Math.floorMod(p.postingId().hashCode(), 50),
                                List.of(), "Fake score for testing"))
                        .toList(),
                new TokenUsage("fake-model", 500, 0, 100), null)).toList());
    }

    private static void sleep(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}   