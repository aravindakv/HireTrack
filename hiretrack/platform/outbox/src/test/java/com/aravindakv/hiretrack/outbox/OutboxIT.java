package com.aravindakv.hiretrack.outbox;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.testsupport.ContainersConfig;
import com.aravindakv.hiretrack.testsupport.KafkaProbe;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = OutboxTestApp.class)
@Import(ContainersConfig.class)
class OutboxIT {

    @Autowired OutboxWriter writer;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.testcontainers.kafka.KafkaContainer kafkaContainer;

    @Test
    void committedEventIsPublishedWithUserIdAsKey() {
        EventEnvelope event = EventEnvelope.create("demo.created", 11, "a-1", 0, Map.of("n", 1), json);
        tx.executeWithoutResult(s -> writer.write("outbox-it-commit", event));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?",
                        Boolean.class, event.eventId())).isTrue());

        List<ConsumerRecord<String, String>> records = probe().readAll("outbox-it-commit", Duration.ofSeconds(3));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("11");
        assertThat(EventEnvelope.parse(records.get(0).value(), json).eventId()).isEqualTo(event.eventId());
    }

    @Test
    void rolledBackTransactionPublishesNothing() {
        EventEnvelope event = EventEnvelope.create("demo.created", 12, "a-2", 0, Map.of("n", 2), json);
        tx.executeWithoutResult(s -> {
            writer.write("outbox-it-rollback", event);
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, event.eventId()))
                .isZero();
        assertThat(probe().readAll("outbox-it-rollback", Duration.ofSeconds(3))).isEmpty();
    }

    private KafkaProbe probe() {
        return new KafkaProbe(kafkaContainer.getBootstrapServers());
    }
}