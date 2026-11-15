package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class FeedRuns {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public FeedRuns(JdbcTemplate jdbc, OutboxWriter outbox, ObjectMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
        this.meters = meters;
    }

    /** Creates a run and its command event, in the caller's transaction. Returns null if the run_key exists. */
    public UUID request(long userId, String runKey, String trigger) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO feed_runs (id, run_key, user_id, trigger, status) VALUES (?, ?, ?, ?, 'REQUESTED')
                ON CONFLICT (run_key) DO NOTHING""", id, runKey, userId, trigger);
        if (inserted == 0) return null;
        outbox.write(Topics.FEED_COMMANDS, EventEnvelope.create(EventTypes.FEED_RUN_REQUESTED, userId,
                "feed-run-" + id, 0, Map.of("runId", id.toString(), "trigger", trigger), json));
        return id;
    }

    public void finish(UUID runId, long userId, String status, String error, String trigger) {
        jdbc.update("""
                UPDATE feed_runs SET status = ?, error = ?, resume_text = NULL, updated_at = now() WHERE id = ?""",
                status, error, runId);
        jdbc.update("UPDATE search_profiles SET last_run_at = now(), last_error = ? WHERE user_id = ?", error, userId);
        meters.counter("hiretrack.feed.runs", "trigger", trigger, "status", status).increment();
    }

    public void publishReady(UUID runId, long userId, int newItems) {
        outbox.write(Topics.FEED_EVENTS, EventEnvelope.create(EventTypes.FEED_READY, userId, "feed-run-" + runId, 0,
                Map.of("runId", runId.toString(), "newItems", newItems), json));
    }
}