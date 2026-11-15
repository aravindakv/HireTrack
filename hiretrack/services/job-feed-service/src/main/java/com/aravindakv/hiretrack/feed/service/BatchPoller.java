package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.feed.client.ServiceClients;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import tools.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BatchPoller {

    private static final Logger log = LoggerFactory.getLogger(BatchPoller.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ServiceClients clients;
    private final FeedRuns runs;
    private final FeedProperties.Feed props;
    private final MeterRegistry meters;

    public BatchPoller(JdbcTemplate jdbc, TransactionTemplate tx, ServiceClients clients, FeedRuns runs,
                       FeedProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clients = clients;
        this.runs = runs;
        this.props = props.feed();
        this.meters = meters;
    }

    public void poll() {
        List<String> batches = jdbc.queryForList(
                "SELECT DISTINCT ai_batch_id FROM feed_runs WHERE status = 'SCORING' LIMIT 50", String.class);
        for (String batchId : batches) {
            try {
                JsonNode view = clients.batch(batchId);
                String status = view.path("status").asString();
                if (!"ENDED".equals(status) && !"FAILED".equals(status)) continue;
                tx.executeWithoutResult(s -> view.path("items").forEach(this::apply));
            } catch (RuntimeException e) {
                log.warn("Polling ai batch {} failed: {}", batchId, e.getMessage());
            }
        }
    }

    /** Applying the same result twice is harmless: only PENDING items and SCORING runs are updated. */
    private void apply(JsonNode item) {
        UUID runId = UUID.fromString(item.path("customId").asString());
        Map<String, Object> run = jdbc.queryForList(
                "SELECT user_id, trigger, attempts, status FROM feed_runs WHERE id = ?", runId).stream().findFirst().orElse(null);
        if (run == null || !"SCORING".equals(run.get("status"))) return;
        long userId = ((Number) run.get("user_id")).longValue();
        String trigger = (String) run.get("trigger");

        if ("SUCCEEDED".equals(item.path("status").asString())) {
            int scored = 0;
            for (JsonNode s : item.path("scores")) {
                scored += jdbc.update("""
                        UPDATE feed_items SET fit_score = ?, missing_skills = ?::jsonb, reason = ?, status = 'NEW'
                        WHERE run_id = ? AND posting_id = ? AND status = 'PENDING'""",
                        s.path("fitScore").asInt(), s.path("missingSkills").toString(), s.path("reason").asString(),
                        runId, Long.parseLong(s.path("postingId").asString()));
            }
            jdbc.update("UPDATE feed_items SET status = 'FILTERED' WHERE run_id = ? AND status = 'PENDING'", runId);
            meters.counter("hiretrack.feed.postings", "stage", "scored").increment(scored);
            runs.finish(runId, userId, "DONE", null, trigger);
            runs.publishReady(runId, userId, scored);
        } else {
            int attempts = ((Number) run.get("attempts")).intValue() + 1;
            if (attempts < props.maxScoreAttempts()) {
                jdbc.update("""
                        UPDATE feed_runs SET status = 'READY_TO_SCORE', attempts = ?, submission_id = NULL,
                               ai_batch_id = NULL, updated_at = now() WHERE id = ?""", attempts, runId);
            } else {
                jdbc.update("UPDATE feed_items SET status = 'FILTERED' WHERE run_id = ? AND status = 'PENDING'", runId);
                runs.finish(runId, userId, "FAILED", "Scoring failed. Will retry tomorrow.", trigger);
            }
        }
    }
}