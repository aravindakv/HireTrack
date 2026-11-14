package com.aravindakv.hiretrack.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;

import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate          // Spring Boot 4: TestRestTemplate is no longer configured automatically
@Import({ContainersConfig.class, TestSecurityConfig.class})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired protected TestRestTemplate http;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired(required = false) private KafkaContainer kafkaContainer;

    protected HttpHeaders auth(long userId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestJwt.token(userId));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected HttpHeaders authWithKey(long userId, String idempotencyKey) {
        HttpHeaders h = auth(userId);
        h.set("Idempotency-Key", idempotencyKey);
        return h;
    }

    protected HttpHeaders internal() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL_TOKEN);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected KafkaProbe kafka() {
        return new KafkaProbe(kafkaContainer.getBootstrapServers());
    }
}