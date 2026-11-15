package com.aravindakv.hiretrack.match.client;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.InternalRestClients;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class Clients {

    public record ActiveResume(long resumeId, String content) {}

    public record AiMatch(int fitScore, List<String> missingSkills, List<String> suggestedBullets,
                          String company, String role, String provider, String model) {}

    private final RestClient resume;
    private final RestClient ai;

    public Clients(RestClient.Builder builder, ObjectMapper json,
                   @Value("${hiretrack.internal.token}") String token,
                   @Value("${hiretrack.clients.resume-url}") String resumeUrl,
                   @Value("${hiretrack.clients.ai-url}") String aiUrl,
                   @Value("${hiretrack.clients.resume-timeout}") Duration resumeTimeout,
                   @Value("${hiretrack.clients.ai-timeout}") Duration aiTimeout) {
        this.resume = InternalRestClients.create(builder, resumeUrl, token, resumeTimeout, json);
        this.ai = InternalRestClients.create(builder, aiUrl, token, aiTimeout, json);
    }

    /** No active resume becomes 409 for the browser (spec 3.3). */
    public ActiveResume activeResume(long userId) {
        try {
            return resume.get().uri("/internal/users/{id}/active-resume", userId).retrieve().body(ActiveResume.class);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.conflict("NO_ACTIVE_RESUME", "Add a resume before running a match.");
            }
            throw e;
        }
    }

    public AiMatch match(long userId, String requestId, String resumeText, String jobDescription) {
        return ai.post().uri("/internal/match")
                .body(Map.of("userId", userId, "requestId", requestId,
                        "resumeText", resumeText, "jobDescription", jobDescription))
                .retrieve().body(AiMatch.class);
    }
}