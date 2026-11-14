package com.aravindakv.hiretrack.common.events;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * No ordering against Spring Boot's own auto-configurations is needed: these beans only take
 * JdbcTemplate/KafkaTemplate as constructor arguments, and Spring creates those first.
 */
@AutoConfiguration
public class EventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProcessedEvents processedEvents(JdbcTemplate jdbc) {
        return new ProcessedEvents(jdbc);
    }

    /** 3 retries one second apart, then the record goes to <topic>.DLT so the partition keeps moving. */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.deadLetter(record.topic()), -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);   // malformed events: straight to DLT
        return handler;
    }
}