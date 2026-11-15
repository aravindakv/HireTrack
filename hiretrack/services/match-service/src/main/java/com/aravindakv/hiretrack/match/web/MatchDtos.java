package com.aravindakv.hiretrack.match.web;

import com.aravindakv.hiretrack.match.domain.Match;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class MatchDtos {

    private MatchDtos() {}

    public record RunMatch(@NotBlank @Size(max = 20000) String jobDescription) {}

    public record MatchResponse(long id, long resumeId, int fitScore, List<String> missingSkills,
                                List<String> suggestedBullets, String company, String role,
                                String jobDescription, Instant createdAt) {
        public static MatchResponse of(Match m) {
            return new MatchResponse(m.getId(), m.getResumeId(), m.getFitScore(), m.getMissingSkills(),
                    m.getSuggestedBullets(), m.getCompany(), m.getRole(), m.getJobDescription(), m.getCreatedAt());
        }
    }

    public record MatchRef(long id, String company, String role) {}
}