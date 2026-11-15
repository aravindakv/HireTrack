package com.aravindakv.hiretrack.ai.service;

import com.aravindakv.hiretrack.ai.model.FeedScoreRequest;
import com.aravindakv.hiretrack.ai.provider.AiProvider;
import com.aravindakv.hiretrack.common.web.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FeedBatchService {

    private static final Logger log = LoggerFactory.getLogger(FeedBatchService.class);

    public record ItemView(String customId, long userId, String status, Object scores, String error) {}

    public record BatchView(UUID batchId, String status, List<ItemView> items) {}

    private final AiProvider provider;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final CostCalculator costs;
    private final UsageLedger ledger;
    private final AiMetrics metrics;

    public FeedBatchService(AiProvider provider, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                            CostCalculator costs, UsageLedger ledger, AiMetrics metrics) {
        this.provider = provider;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.costs = costs;
        this.ledger = ledger;
        this.metrics = metrics;
    }

    /** Idempotent on submissionId: resubmitting after a crash returns the existing batch, no second charge. */
    public UUID submit(String submissionId, List<FeedScoreRequest> items) {
        if (items.isEmpty() || items.size() > 1000) {
            throw ApiException.badRequest("BATCH_SIZE", "A batch needs 1 to 1000 items.");
        }
        List<UUID> existing = jdbc.queryForList("SELECT id FROM ai_batches WHERE submission_id = ?", UUID.class, submissionId);
        if (!existing.isEmpty()) return existing.get(0);

        String providerBatchId = provider.submitFeedBatch(items);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ai_batches (id, submission_id, provider, provider_batch_id, model, status, request_count)
                    VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?)""",
                    id, submissionId, provider.name(), providerBatchId, provider.feedModel(), items.size());
            for (FeedScoreRequest item : items) {
                jdbc.update("""
                        INSERT INTO ai_batch_items (batch_id, custom_id, user_id, status, result)
                        VALUES (?, ?, ?, 'PENDING', ?::jsonb)""",
                        id, item.customId(), item.userId(), toJson(Map.of("request", item)));
            }
        });
        metrics.request(provider.name(), "FEED_SCORE", provider.feedModel(), "submitted");
        return id;
    }

    /** Called by the poller every minute (one pod at a time, via ShedLock). */
    public void pollOpenBatches() {
        List<Map<String, Object>> open = jdbc.queryForList(
                "SELECT id, provider_batch_id FROM ai_batches WHERE status = 'SUBMITTED' ORDER BY created_at LIMIT 50");
        for (Map<String, Object> b : open) {
            UUID id = (UUID) b.get("id");
            try {
                pollOne(id, (String) b.get("provider_batch_id"));
            } catch (RuntimeException e) {
                log.warn("Polling batch {} failed; will retry: {}", id, e.getMessage());
            }
        }
    }

    void pollOne(UUID batchId, String providerBatchId) {
        List<FeedScoreRequest> submitted = jdbc.query(
                "SELECT result->'request' AS req FROM ai_batch_items WHERE batch_id = ?",
                (rs, i) -> fromJson(rs.getString("req"), FeedScoreRequest.class), batchId);
        AiProvider.BatchPoll poll = provider.pollFeedBatch(providerBatchId, submitted);
        if (poll.state() == AiProvider.BatchState.IN_PROGRESS) return;

        tx.executeWithoutResult(s -> {
            for (AiProvider.ItemResult r : poll.items()) {
                Long userId = jdbc.queryForObject("SELECT user_id FROM ai_batch_items WHERE batch_id = ? AND custom_id = ?",
                        Long.class, batchId, r.customId());
                if (userId == null) continue;
                if (r.usage() != null) {
                    var cost = costs.cost(r.usage(), true);
                    ledger.record(batchId + ":" + r.customId(), userId, provider.name(), "FEED_SCORE", r.usage(), cost);
                    metrics.usage(provider.name(), "FEED_SCORE", r.usage(), cost);
                }
                jdbc.update("""
                        UPDATE ai_batch_items SET status = ?, result = result || ?::jsonb, error = ?
                        WHERE batch_id = ? AND custom_id = ?""",
                        r.succeeded() ? "SUCCEEDED" : "FAILED", toJson(Map.of("scores", r.scores())), r.error(),
                        batchId, r.customId());
                metrics.request(provider.name(), "FEED_SCORE", provider.feedModel(), r.succeeded() ? "ok" : "invalid");
            }
            jdbc.update("UPDATE ai_batches SET status = ?, ended_at = now() WHERE id = ?",
                    poll.state() == AiProvider.BatchState.FAILED ? "FAILED" : "ENDED", batchId);
        });
    }

    public BatchView view(UUID batchId) {
        List<String> status = jdbc.queryForList("SELECT status FROM ai_batches WHERE id = ?", String.class, batchId);
        if (status.isEmpty()) throw ApiException.notFound("Batch");
        List<ItemView> items = jdbc.query("""
                SELECT custom_id, user_id, status, result->'scores' AS scores, error
                FROM ai_batch_items WHERE batch_id = ? ORDER BY custom_id""",
                (rs, i) -> new ItemView(rs.getString("custom_id"), rs.getLong("user_id"), rs.getString("status"),
                        rs.getString("scores") == null ? List.of()
                                : fromJson(rs.getString("scores"), new TypeReference<List<Object>>() {}),
                        rs.getString("error")),
                batchId);
        return new BatchView(batchId, status.get(0), items);
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); } catch (JacksonException e) { throw new IllegalStateException(e); }
    }

    private <T> T fromJson(String s, Class<T> type) {
        try { return json.readValue(s, type); } catch (JacksonException e) { throw new IllegalStateException(e); }
    }

    private <T> T fromJson(String s, TypeReference<T> type) {
        try { return json.readValue(s, type); } catch (JacksonException e) { throw new IllegalStateException(e); }
    }
}