package com.aravindakv.hiretrack.analytics;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AnalyticsIT extends IntegrationTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;

    private void send(EventEnvelope e) {
        kafka.send("application-events", Long.toString(e.userId()), e.toJson(json));
    }

    private EventEnvelope app(String type, long user, long appId, long version, String stage, LocalDate applied, Instant responded) {
        Map<String, Object> p = new HashMap<>();
        p.put("applicationId", appId);
        p.put("stage", stage);
        p.put("appliedDate", applied == null ? null : applied.toString());
        p.put("respondedAt", responded == null ? null : responded.toString());
        return EventEnvelope.create(type, user, "application-" + appId, version, p, json);
    }

    private JsonNode dashboard(long user) {
        return http.exchange("/dashboard?tz=UTC", HttpMethod.GET, new HttpEntity<>(auth(user)), JsonNode.class).getBody();
    }

    @Test
    void stageCountsAndResponseRateFollowEvents() {
        LocalDate today = LocalDate.now();
        send(app("application.created", 1001, 1, 0, "WISHLIST", null, null));
        send(app("application.stage-changed", 1001, 1, 1, "APPLIED", today, null));
        send(app("application.stage-changed", 1001, 1, 2, "REJECTED", today, Instant.now()));
        send(app("application.created", 1001, 2, 0, "WISHLIST", null, null));
        send(app("application.stage-changed", 1001, 2, 1, "APPLIED", today, null));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode d = dashboard(1001);
            assertThat(d.at("/byStage/REJECTED").asLong()).isEqualTo(1);
            assertThat(d.at("/byStage/APPLIED").asLong()).isEqualTo(1);
            assertThat(d.get("responseRate").asDouble()).isEqualTo(0.5);
        });
    }

    @Test
    void duplicateDeliveryIsAppliedOnce() {
        EventEnvelope created = app("application.created", 1002, 10, 0, "WISHLIST", null, null);
        send(created);
        send(created);                                           // same eventId twice
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(dashboard(1002).at("/byStage/WISHLIST").asLong()).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                created.eventId())).isEqualTo(1);
    }

    @Test
    void olderVersionArrivingLateDoesNotOverwrite() {
        send(app("application.stage-changed", 1003, 20, 2, "OFFER", LocalDate.now(), Instant.now()));
        send(app("application.stage-changed", 1003, 20, 1, "APPLIED", LocalDate.now(), null));   // late, older
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT stage FROM app_projection WHERE application_id = 20", String.class))
                        .isEqualTo("OFFER"));
    }

    @Test
    void deletedApplicationStaysDeletedEvenIfAnOldEventArrivesLater() {
        send(app("application.created", 1004, 30, 0, "WISHLIST", null, null));
        send(app("application.deleted", 1004, 30, 0, "WISHLIST", null, null));
        send(app("application.updated", 1004, 30, 1, "WISHLIST", null, null));                 // straggler
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM app_projection WHERE application_id = 30", Integer.class))
                        .isZero());
    }

    @Test
    void interviewsThisWeekCountsOnlyThisWeek() {
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant inTwoWeeks = Instant.now().plus(14, ChronoUnit.DAYS);
        send(app("application.created", 1005, 40, 0, "INTERVIEW", LocalDate.now(), Instant.now()));
        send(EventEnvelope.create("interview.scheduled", 1005, "interview-41", 0,
                Map.of("interviewId", 41, "applicationId", 40, "scheduledAt", inTwoWeeks.toString()), json));
        send(EventEnvelope.create("interview.scheduled", 1005, "interview-42", 0,
                Map.of("interviewId", 42, "applicationId", 40, "scheduledAt", tomorrow.toString()), json));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_projection WHERE user_id = 1005", Integer.class))
                        .isEqualTo(2));
        // "tomorrow" may fall into next week on Sundays; the unit tests cover boundaries exactly
        long expected = Instant.now().atZone(java.time.ZoneOffset.UTC).getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? 0 : 1;
        assertThat(dashboard(1005).get("interviewsThisWeek").asLong()).isEqualTo(expected);
    }

    @Test
    void invalidTimezoneIs400() {
        var res = http.exchange("/dashboard?tz=Mars/Base", HttpMethod.GET, new HttpEntity<>(auth(1006)), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("INVALID_TIMEZONE");
    }

    @Test
    void nothingSentShowsNullResponseRate() {
        assertThat(dashboard(1007).get("responseRate").isNull()).isTrue();
    }
}