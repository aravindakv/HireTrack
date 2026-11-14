package com.aravindakv.hiretrack.testsupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Containers start once per Spring test context and are shared by every test class that
 * uses the same configuration. @ServiceConnection wires their addresses into Spring Boot.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:3.8.0");
    }

    // Only modules with spring-data-redis can connect to it; the others would fail to start.
    @Bean
    @ServiceConnection(name = "redis")
    @ConditionalOnClass(name = "org.springframework.data.redis.connection.RedisConnectionFactory")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}