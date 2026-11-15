package com.aravindakv.hiretrack.feed.messaging;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.ProcessedEvents;
import com.aravindakv.hiretrack.common.events.Topics;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Spec 10.4 cost lever 1: only users seen in the last 14 days get a daily run. */
@Component
public class UserActivityListener {

    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final ObjectMapper json;

    public UserActivityListener(JdbcTemplate jdbc, ProcessedEvents processed, ObjectMapper json) {
        this.jdbc = jdbc;
        this.processed = processed;
        this.json = json;
    }

    @KafkaListener(topics = Topics.IDENTITY_EVENTS, groupId = "job-feed-activity")
    @Transactional
    public void on(String value) {
        EventEnvelope e = EventEnvelope.parse(value, json);
        if (!processed.markProcessed(e.eventId())) return;
        if (!EventTypes.USER_SEEN.equals(e.type()) && !EventTypes.USER_REGISTERED.equals(e.type())) return;
        LocalDate day = e.payload().hasNonNull("seenOn")
                ? LocalDate.parse(e.payload().get("seenOn").asString())
                : e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        jdbc.update("""
                INSERT INTO user_activity (user_id, last_seen_on) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET last_seen_on = GREATEST(user_activity.last_seen_on, EXCLUDED.last_seen_on)""",
                e.userId(), Date.valueOf(day));
    }
}