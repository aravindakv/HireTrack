package com.aravindakv.hiretrack.feed.client;

import com.aravindakv.hiretrack.common.web.InternalRestClients;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ServiceClients {

    public record ActiveResume(long resumeId, String content) {}

    public record ScoreItem(String customId, long userId, String resumeText, List<Map<String, String>> postings) {}

    private final RestClient resume;
    private final RestClient ai;
    private final RestClient application;

    public ServiceClients(RestClient.Builder builder, ObjectMapper json, @Value("${hiretrack.internal.token}") String token,
                          @Value("${hiretrack.clients.resume-url}") String resumeUrl,
                          @Value("${hiretrack.clients.ai-url}") String aiUrl,
                          @Value("${hiretrack.clients.application-url}") String applicationUrl) {
        this.resume = InternalRestClients.create(builder, resumeUrl, token, Duration.ofSeconds(3), json);
        this.ai = InternalRestClients.create(builder, aiUrl, token, Duration.ofSeconds(120), json);
        this.application = InternalRestClients.create(builder, applicationUrl, token, Duration.ofSeconds(5), json);
    }

    public ActiveResume activeResume(long userId) {
        return resume.get().uri("/internal/users/{id}/active-resume", userId).retrieve().body(ActiveResume.class);
    }

    public String submitBatch(String submissionId, List<ScoreItem> items) {
        JsonNode res = ai.post().uri("/internal/feed-batches")
                .body(Map.of("submissionId", submissionId, "items", items)).retrieve().body(JsonNode.class);
        return res.get("batchId").asString();
    }

    public JsonNode batch(String batchId) {
        return ai.get().uri("/internal/feed-batches/{id}", batchId).retrieve().body(JsonNode.class);
    }

    public long createApplication(long userId, String idempotencyKey, Map<String, Object> body) {
        JsonNode res = application.post().uri("/internal/users/{id}/applications", userId)
                .header("Idempotency-Key", idempotencyKey).body(body).retrieve().body(JsonNode.class);
        return res.get("id").asLong();
    }
}