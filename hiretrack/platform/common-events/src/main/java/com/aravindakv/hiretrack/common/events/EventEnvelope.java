package com.aravindakv.hiretrack.common.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * The wrapper for every event on Kafka.
 * eventId          unique per event; consumers deduplicate on it
 * userId           also the Kafka record key, so one user's events stay in order on one partition
 * aggregateVersion the owning row's @Version after the change; projections ignore older versions
 */
public record EventEnvelope(
        UUID eventId,
        String type,
        int schemaVersion,
        long userId,
        String aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        JsonNode payload) {

    public static EventEnvelope create(String type, long userId, String aggregateId, long aggregateVersion,
                                       Object payload, ObjectMapper json) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, userId, aggregateId, aggregateVersion,
                Instant.now(), json.valueToTree(payload));
    }

    public <T> T payloadAs(Class<T> type, ObjectMapper json) {
        try {
            return json.treeToValue(payload, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Payload of " + this.type + " is not a " + type.getSimpleName(), e);
        }
    }

    public static EventEnvelope parse(String value, ObjectMapper json) {
        try {
            return json.readValue(value, EventEnvelope.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not an EventEnvelope: " + e.getOriginalMessage(), e);
        }
    }

    public String toJson(ObjectMapper json) {
        try {
            return json.writeValueAsString(this);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}