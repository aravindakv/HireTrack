package com.aravindakv.hiretrack.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes unpublished outbox rows to Kafka, oldest first.
 * FOR UPDATE SKIP LOCKED lets several pods run the relay without sending a row twice at the same time.
 * If a pod dies after sending but before marking rows published, the rows are sent again:
 * delivery is at-least-once, and consumers deduplicate on eventId.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       OutboxProperties props) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${hiretrack.outbox.relay-delay-ms:500}")
    public void relay() {
        Integer sent;
        do {
            sent = tx.execute(status -> publishBatch());
        } while (sent != null && sent == props.batchSize());
    }

    int publishBatch() {
        List<Row> rows = jdbc.query("""
                SELECT id, topic, record_key, payload::text AS payload
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("record_key"), rs.getString("payload")),
                props.batchSize());
        if (rows.isEmpty()) return 0;

        for (Row row : rows) {
            try {
                kafka.send(row.topic(), row.recordKey(), row.payload()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Outbox publish failed for {}; will retry: {}", row.id(), e.getMessage());
                throw new IllegalStateException("Kafka unavailable", e);   // roll back; rows stay unpublished
            }
        }
        UUID[] ids = rows.stream().map(Row::id).toArray(UUID[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE outbox SET published_at = now() WHERE id = ANY(?)");
            Array array = con.createArrayOf("uuid", ids);
            ps.setArray(1, array);
            return ps;
        });
        return rows.size();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void deletePublishedRows() {
        int deleted = jdbc.update(
                "DELETE FROM outbox WHERE published_at < now() - make_interval(days => ?)", props.retentionDays());
        log.info("Outbox cleanup removed {} rows", deleted);
    }

    record Row(UUID id, String topic, String recordKey, String payload) {}
}