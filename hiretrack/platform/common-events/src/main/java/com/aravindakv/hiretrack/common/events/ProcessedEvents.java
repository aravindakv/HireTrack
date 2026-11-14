package com.aravindakv.hiretrack.common.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Consumer-side deduplication. Call inside the same transaction that applies the event:
 * if the transaction rolls back, the event is not marked processed and will be retried.
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true the first time this event ID is seen, false for duplicates. */
    public boolean markProcessed(UUID eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("markProcessed must run inside a transaction");
        }
        return jdbc.update("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 1;
    }
}