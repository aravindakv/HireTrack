package com.aravindakv.hiretrack.ai.model;

import java.util.List;

/** One user's feed run: the resume plus up to 15 posting snippets. customId = feedRunId. */
public record FeedScoreRequest(String customId, long userId, String resumeText, List<Posting> postings) {
    public record Posting(String postingId, String title, String company, String snippet) {}
}