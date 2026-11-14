package com.aravindakv.hiretrack.outbox;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        return new OutboxWriter(jdbc, json);
    }

    @Bean
    @ConditionalOnProperty(name = "hiretrack.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            OutboxProperties props, MeterRegistry meters) {
        Gauge.builder("hiretrack.outbox.oldest.unpublished", jdbc, j -> {
                    Double seconds = j.queryForObject(
                            "SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) FROM outbox WHERE published_at IS NULL",
                            Double.class);
                    return seconds == null ? 0 : seconds;
                })
                .baseUnit("seconds")
                .description("Age of the oldest event not yet sent to Kafka")
                .register(meters);
        return new OutboxRelay(jdbc, kafka, tx, props);
    }
}