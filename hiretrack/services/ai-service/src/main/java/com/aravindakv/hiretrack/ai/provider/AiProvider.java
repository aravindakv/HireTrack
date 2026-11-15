package com.aravindakv.hiretrack.ai.provider;

import com.aravindakv.hiretrack.ai.model.FeedScore;
import com.aravindakv.hiretrack.ai.model.FeedScoreRequest;
import com.aravindakv.hiretrack.ai.model.MatchResult;
import com.aravindakv.hiretrack.ai.model.TokenUsage;

import java.util.List;

/** Everything the rest of ai-service knows about an AI vendor. */
public interface AiProvider {

    String name();                         // "openai" | "anthropic"

    String feedModel();

    record MatchCompletion(MatchResult result, TokenUsage usage) {}

    /** Real-time match. Throws AiRejectedException (refusal, truncation, bad JSON) or AiUpstreamException. */
    MatchCompletion match(String resumeText, String jobDescription);

    /** Submits one batch request per item; returns the provider's batch ID. */
    String submitFeedBatch(List<FeedScoreRequest> items);

    enum BatchState { IN_PROGRESS, ENDED, FAILED }

    record ItemResult(String customId, boolean succeeded, List<FeedScore> scores, TokenUsage usage, String error) {}

    record BatchPoll(BatchState state, List<ItemResult> items) {}

    /** Polls a batch. When ENDED, items holds a result for every request the provider returned. */
    BatchPoll pollFeedBatch(String providerBatchId, List<FeedScoreRequest> submitted);
}