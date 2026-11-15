package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.feed.client.ServiceClients;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Groups READY_TO_SCORE runs into one ai-service batch. The submission ID is stored on the runs
 * BEFORE calling ai-service; if the call's answer is lost, the next attempt reuses the same
 * submission ID and ai-service returns the existing batch (no second charge).
 */
@Service
public class BatchSubmitter {

    private static final Logger log = LoggerFactory.getLogger(BatchSubmitter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ServiceClients clients;
    private final FeedProperties.Feed props;

    public BatchSubmitter(JdbcTemplate jdbc, TransactionTemplate tx, ServiceClients clients, FeedProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clients = clients;
        this.props = props.feed();
    }

    public void submit() {
        // Retry submissions whose answer was lost (stuck in SUBMITTING for 10+ minutes)
        List<String> stuck = jdbc.queryForList("""
                SELECT DISTINCT submission_id FROM feed_runs
                WHERE status = 'SUBMITTING' AND updated_at < now() - interval '10 minutes'""", String.class);
        stuck.forEach(this::send);

        String submissionId = UUID.randomUUID().toString();
        Integer claimed = tx.execute(s -> jdbc.update("""
                UPDATE feed_runs SET status = 'SUBMITTING', submission_id = ?, updated_at = now()
                WHERE id IN (SELECT id FROM feed_runs WHERE status = 'READY_TO_SCORE'
                             ORDER BY updated_at LIMIT ? FOR UPDATE SKIP LOCKED)""",
                submissionId, props.submitBatchSize()));
        if (claimed != null && claimed > 0) send(submissionId);
    }

    private void send(String submissionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, resume_text FROM feed_runs WHERE submission_id = ? AND status = 'SUBMITTING'", submissionId);
        if (rows.isEmpty()) return;
        List<ServiceClients.ScoreItem> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID runId = (UUID) r.get("id");
            List<Map<String, String>> postings = jdbc.query("""
                    SELECT p.id, p.title, coalesce(p.company, '') AS company, p.snippet
                    FROM feed_items i JOIN postings p ON p.id = i.posting_id
                    WHERE i.run_id = ? AND i.status = 'PENDING'""",
                    (rs, n) -> Map.of("postingId", Long.toString(rs.getLong("id")), "title", rs.getString("title"),
                            "company", rs.getString("company"), "snippet", rs.getString("snippet")),
                    runId);
            items.add(new ServiceClients.ScoreItem(runId.toString(), ((Number) r.get("user_id")).longValue(),
                    (String) r.get("resume_text"), postings));
        }
        try {
            String batchId = clients.submitBatch(submissionId, items);
            jdbc.update("""
                    UPDATE feed_runs SET status = 'SCORING', ai_batch_id = ?, updated_at = now()
                    WHERE submission_id = ? AND status = 'SUBMITTING'""", batchId, submissionId);
        } catch (RuntimeException e) {
            log.warn("Batch submission {} failed; will retry: {}", submissionId, e.getMessage());
        }
    }
}