package com.aravindakv.hiretrack.analytics.messaging;

import com.aravindakv.hiretrack.analytics.service.DashboardCache;
import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.ProcessedEvents;
import com.aravindakv.hiretrack.common.events.Topics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Applies application-service events to the projection tables.
 *  - duplicates: skipped via processed_events (same transaction)
 *  - out of order: an upsert only wins when its aggregateVersion is newer
 *  - after delete: a tombstone blocks older events from recreating the row
 */
@Component
public class Projector {

    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final DashboardCache cache;
    private final ObjectMapper json;

    public Projector(JdbcTemplate jdbc, ProcessedEvents processed, DashboardCache cache, ObjectMapper json) {
        this.jdbc = jdbc;
        this.processed = processed;
        this.cache = cache;
        this.json = json;
    }

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "analytics")
    @Transactional
    public void on(String value) {
        EventEnvelope e = EventEnvelope.parse(value, json);         // malformed → IllegalArgumentException → DLT
        if (!processed.markProcessed(e.eventId())) return;
        JsonNode p = e.payload();
        switch (e.type()) {
            case EventTypes.APPLICATION_CREATED, EventTypes.APPLICATION_UPDATED, EventTypes.APPLICATION_STAGE_CHANGED ->
                    upsertApplication(e, p);
            case EventTypes.APPLICATION_DELETED -> deleteApplication(e, p.get("applicationId").asLong());
            case EventTypes.INTERVIEW_SCHEDULED, EventTypes.INTERVIEW_UPDATED -> upsertInterview(e, p);
            case EventTypes.INTERVIEW_DELETED -> deleteInterview(e, p.get("interviewId").asLong());
            default -> { /* unknown types are ignored, so producers can add events safely */ }
        }
        long userId = e.userId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.evict(userId); }
        });
    }

    private void upsertApplication(EventEnvelope e, JsonNode p) {
        if (tombstoned(e.aggregateId())) return;
        jdbc.update("""
                INSERT INTO app_projection (application_id, user_id, stage, applied_date, responded_at, version)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (application_id) DO UPDATE
                   SET stage = EXCLUDED.stage, applied_date = EXCLUDED.applied_date,
                       responded_at = EXCLUDED.responded_at, version = EXCLUDED.version
                 WHERE app_projection.version < EXCLUDED.version""",
                p.get("applicationId").asLong(), e.userId(), p.get("stage").asString(),
                date(p.get("appliedDate")), timestamp(p.get("respondedAt")), e.aggregateVersion());
    }

    private void deleteApplication(EventEnvelope e, long applicationId) {
        jdbc.update("INSERT INTO deleted_aggregates (aggregate_id) VALUES (?) ON CONFLICT DO NOTHING", e.aggregateId());
        jdbc.update("DELETE FROM app_projection WHERE application_id = ?", applicationId);
        jdbc.update("DELETE FROM interview_projection WHERE application_id = ?", applicationId);   // cascaded in the source
    }

    private void upsertInterview(EventEnvelope e, JsonNode p) {
        long applicationId = p.get("applicationId").asLong();
        if (tombstoned(e.aggregateId()) || tombstoned("application-" + applicationId)) return;
        jdbc.update("""
                INSERT INTO interview_projection (interview_id, application_id, user_id, scheduled_at, version)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (interview_id) DO UPDATE
                   SET scheduled_at = EXCLUDED.scheduled_at, version = EXCLUDED.version
                 WHERE interview_projection.version < EXCLUDED.version""",
                p.get("interviewId").asLong(), applicationId, e.userId(),
                timestamp(p.get("scheduledAt")), e.aggregateVersion());
    }

    private void deleteInterview(EventEnvelope e, long interviewId) {
        jdbc.update("INSERT INTO deleted_aggregates (aggregate_id) VALUES (?) ON CONFLICT DO NOTHING", e.aggregateId());
        jdbc.update("DELETE FROM interview_projection WHERE interview_id = ?", interviewId);
    }

    private boolean tombstoned(String aggregateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM deleted_aggregates WHERE aggregate_id = ?)", Boolean.class, aggregateId));
    }

    private static Date date(JsonNode n) {
        return n == null || n.isNull() ? null : Date.valueOf(LocalDate.parse(n.asString()));
    }

    private static Timestamp timestamp(JsonNode n) {
        return n == null || n.isNull() ? null : Timestamp.from(Instant.parse(n.asString()));
    }
}