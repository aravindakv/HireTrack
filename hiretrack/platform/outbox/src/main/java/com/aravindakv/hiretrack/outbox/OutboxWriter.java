package com.aravindakv.hiretrack.outbox;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stores an event in the outbox table as part of the caller's transaction. */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void write(String topic, EventEnvelope event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must join the transaction that changes the data");
        }
        jdbc.update("""
                INSERT INTO outbox (id, topic, record_key, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)""",
                event.eventId(), topic, Long.toString(event.userId()), event.type(), event.toJson(json));
    }
}