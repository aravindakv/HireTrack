package com.aravindakv.hiretrack.ai.web;

import com.aravindakv.hiretrack.ai.model.FeedScoreRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AiDtos {

    private AiDtos() {}

    public record MatchRequest(@Positive long userId,
                               @NotBlank @Size(max = 200) String requestId,
                               @NotBlank @Size(max = 20000) String resumeText,
                               @NotBlank @Size(max = 20000) String jobDescription) {}

    public record MatchResponse(int fitScore, List<String> missingSkills, List<String> suggestedBullets,
                                String company, String role, String provider, String model) {}

    public record FeedBatchRequest(@NotBlank @Size(max = 200) String submissionId,
                                   @NotEmpty @Size(max = 1000) List<@Valid FeedScoreRequest> items) {}

    public record FeedBatchCreated(String batchId) {}
}