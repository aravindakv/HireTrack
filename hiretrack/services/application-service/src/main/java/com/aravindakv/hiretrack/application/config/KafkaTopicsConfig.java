package com.aravindakv.hiretrack.application.config;

import com.aravindakv.hiretrack.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {
    @Bean
    NewTopic applicationEventsTopic(@Value("${hiretrack.kafka.partitions}") int partitions,
                                    @Value("${hiretrack.kafka.replicas}") int replicas) {
        return TopicBuilder.name(Topics.APPLICATION_EVENTS).partitions(partitions).replicas(replicas).build();
    }
}