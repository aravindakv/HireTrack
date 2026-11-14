package com.aravindakv.hiretrack.idempotency;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    IdempotencyService idempotencyService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                                          MeterRegistry meters, IdempotencyProperties props) {
        return new IdempotencyService(jdbc, tx, json, meters, props);
    }
}