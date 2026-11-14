# Phase 7 — Analytics Service (Dashboard)

**Goal:** the job-hunt dashboard (spec 3.5) as a **read model built from events** (CQRS). analytics-service never queries application-service. It consumes `application-events`, keeps its own projection tables, and answers `GET /dashboard` from them. Duplicate and out-of-order events are both harmless.

**Time:** 1–2 days.

**Runs on its own with:** Docker Compose infrastructure. You publish events by hand with the Kafka console producer, so application-service doesn't need to run.

---

## Step 7.1 — Module and build file

Add to `settings.gradle.kts`:

```kotlin
include("services:analytics-service")
```

`services/analytics-service/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:common-events"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
}
```

`src/main/java/com/aravindakv/hiretrack/analytics/AnalyticsApplication.java`:

```java
package com.aravindakv.hiretrack.analytics;

import com.aravindakv.hiretrack.common.web.EnableHiretrackSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableHiretrackSecurity
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
```

## Step 7.2 — Configuration

`application.yml`: the Phase 1 template with `<service>` = `analytics-service`, `<schema>` = `analytics`, `<port>` = `8087`. Remove the `spring.jpa` block (this service uses JDBC only), and set flyway locations to `classpath:db/migration,classpath:db/platform/events`. Add:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  kafka:
    consumer:
      group-id: analytics

hiretrack:
  dashboard:
    cache-ttl: 30s
```

## Step 7.3 — Migration

`db/migration/V1__projections.sql`:

```sql
CREATE TABLE app_projection (
    application_id BIGINT      PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    stage          TEXT        NOT NULL,
    applied_date   DATE,
    responded_at   TIMESTAMPTZ,
    version        BIGINT      NOT NULL          -- aggregateVersion of the newest applied event
);
CREATE INDEX app_projection_user_stage ON app_projection (user_id, stage);

CREATE TABLE interview_projection (
    interview_id   BIGINT      PRIMARY KEY,
    application_id BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    scheduled_at   TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL
);
CREATE INDEX interview_projection_user_time ON interview_projection (user_id, scheduled_at);
CREATE INDEX interview_projection_application ON interview_projection (application_id);

-- Tombstones: an older event arriving after a delete must not bring the row back
CREATE TABLE deleted_aggregates (
    aggregate_id TEXT        PRIMARY KEY,        -- "application-12", "interview-5"
    deleted_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## Step 7.4 — Dashboard maths as pure functions

`domain/DashboardMath.java`:

```java
package com.aravindakv.hiretrack.analytics.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class DashboardMath {

    private DashboardMath() {}

    public record Range(Instant from, Instant to) {}            // [from, to)

    /** Spec 3.5: Monday 00:00 to the next Monday 00:00 in the viewer's timezone, as UTC instants. */
    public static Range currentWeek(Instant now, ZoneId zone) {
        ZonedDateTime monday = now.atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone);
        return new Range(monday.toInstant(), monday.plusWeeks(1).toInstant());
    }

    /** replied ÷ sent; null when nothing has been sent (the UI shows "—"). */
    public static Double responseRate(long sent, long replied) {
        return sent == 0 ? null : (double) replied / sent;
    }
}
```

`src/test/java/com/aravindakv/hiretrack/analytics/domain/DashboardMathTest.java`:

```java
package com.aravindakv.hiretrack.analytics.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardMathTest {

    @Test
    void weekStartsMondayMidnightInTheViewersZone() {
        // Wednesday 2026-11-18 10:00 in Kolkata
        var r = DashboardMath.currentWeek(Instant.parse("2026-11-18T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
        assertThat(r.from()).isEqualTo(Instant.parse("2026-11-15T18:30:00Z"));    // Mon 16 Nov 00:00 IST
        assertThat(r.to()).isEqualTo(Instant.parse("2026-11-22T18:30:00Z"));      // Mon 23 Nov 00:00 IST
    }

    @Test
    void sundayLateEveningLocalIsStillTheSameWeek() {
        // Sunday 22 Nov 23:30 IST = 18:00 UTC: already "Monday" nowhere near, still this week locally
        var r = DashboardMath.currentWeek(Instant.parse("2026-11-22T18:00:00Z"), ZoneId.of("Asia/Kolkata"));
        assertThat(r.from()).isEqualTo(Instant.parse("2026-11-15T18:30:00Z"));
    }

    @Test
    void sameInstantIsADifferentWeekInAnotherZone() {
        Instant mondayMorningTokyo = Instant.parse("2026-11-22T23:30:00Z");       // Mon 08:30 Tokyo, Sun 15:30 New York
        assertThat(DashboardMath.currentWeek(mondayMorningTokyo, ZoneId.of("Asia/Tokyo")).from())
                .isEqualTo(Instant.parse("2026-11-22T15:00:00Z"));
        assertThat(DashboardMath.currentWeek(mondayMorningTokyo, ZoneId.of("America/New_York")).from())
                .isEqualTo(Instant.parse("2026-11-16T05:00:00Z"));
    }

    @Test
    void responseRateIsNullBeforeAnythingIsSent() {
        assertThat(DashboardMath.responseRate(0, 0)).isNull();
        assertThat(DashboardMath.responseRate(4, 1)).isEqualTo(0.25);
    }
}
```

**Verify**

```bash
./gradlew :services:analytics-service:test --tests '*DashboardMathTest'
```

Expected: 4 tests pass.

## Step 7.5 — Projector (the event consumer)

`messaging/Projector.java`:

```java
package com.aravindakv.hiretrack.analytics.messaging;

import com.aravindakv.hiretrack.analytics.service.DashboardCache;
import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.ProcessedEvents;
import com.aravindakv.hiretrack.common.events.Topics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Applies application-service events to the projection tables.
 *  - duplicates: skipped via processed_events (same transaction)
 *  - out of order: an upsert only wins when its aggregateVersion is newer
 *  - after delete: a tombstone blocks older events from recreating the row
 */
@Component
public class Projector {

    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final DashboardCache cache;
    private final ObjectMapper json;

    public Projector(JdbcTemplate jdbc, ProcessedEvents processed, DashboardCache cache, ObjectMapper json) {
        this.jdbc = jdbc;
        this.processed = processed;
        this.cache = cache;
        this.json = json;
    }

    @KafkaListener(topics = Topics.APPLICATION_EVENTS, groupId = "analytics")
    @Transactional
    public void on(String value) {
        EventEnvelope e = EventEnvelope.parse(value, json);         // malformed → IllegalArgumentException → DLT
        if (!processed.markProcessed(e.eventId())) return;
        JsonNode p = e.payload();
        switch (e.type()) {
            case EventTypes.APPLICATION_CREATED, EventTypes.APPLICATION_UPDATED, EventTypes.APPLICATION_STAGE_CHANGED ->
                    upsertApplication(e, p);
            case EventTypes.APPLICATION_DELETED -> deleteApplication(e, p.get("applicationId").asLong());
            case EventTypes.INTERVIEW_SCHEDULED, EventTypes.INTERVIEW_UPDATED -> upsertInterview(e, p);
            case EventTypes.INTERVIEW_DELETED -> deleteInterview(e, p.get("interviewId").asLong());
            default -> { /* unknown types are ignored, so producers can add events safely */ }
        }
        long userId = e.userId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.evict(userId); }
        });
    }

    private void upsertApplication(EventEnvelope e, JsonNode p) {
        if (tombstoned(e.aggregateId())) return;
        jdbc.update("""
                INSERT INTO app_projection (application_id, user_id, stage, applied_date, responded_at, version)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (application_id) DO UPDATE
                   SET stage = EXCLUDED.stage, applied_date = EXCLUDED.applied_date,
                       responded_at = EXCLUDED.responded_at, version = EXCLUDED.version
                 WHERE app_projection.version < EXCLUDED.version""",
                p.get("applicationId").asLong(), e.userId(), p.get("stage").asString(),
                date(p.get("appliedDate")), timestamp(p.get("respondedAt")), e.aggregateVersion());
    }

    private void deleteApplication(EventEnvelope e, long applicationId) {
        jdbc.update("INSERT INTO deleted_aggregates (aggregate_id) VALUES (?) ON CONFLICT DO NOTHING", e.aggregateId());
        jdbc.update("DELETE FROM app_projection WHERE application_id = ?", applicationId);
        jdbc.update("DELETE FROM interview_projection WHERE application_id = ?", applicationId);   // cascaded in the source
    }

    private void upsertInterview(EventEnvelope e, JsonNode p) {
        long applicationId = p.get("applicationId").asLong();
        if (tombstoned(e.aggregateId()) || tombstoned("application-" + applicationId)) return;
        jdbc.update("""
                INSERT INTO interview_projection (interview_id, application_id, user_id, scheduled_at, version)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (interview_id) DO UPDATE
                   SET scheduled_at = EXCLUDED.scheduled_at, version = EXCLUDED.version
                 WHERE interview_projection.version < EXCLUDED.version""",
                p.get("interviewId").asLong(), applicationId, e.userId(),
                timestamp(p.get("scheduledAt")), e.aggregateVersion());
    }

    private void deleteInterview(EventEnvelope e, long interviewId) {
        jdbc.update("INSERT INTO deleted_aggregates (aggregate_id) VALUES (?) ON CONFLICT DO NOTHING", e.aggregateId());
        jdbc.update("DELETE FROM interview_projection WHERE interview_id = ?", interviewId);
    }

    private boolean tombstoned(String aggregateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM deleted_aggregates WHERE aggregate_id = ?)", Boolean.class, aggregateId));
    }

    private static Date date(JsonNode n) {
        return n == null || n.isNull() ? null : Date.valueOf(LocalDate.parse(n.asString()));
    }

    private static Timestamp timestamp(JsonNode n) {
        return n == null || n.isNull() ? null : Timestamp.from(Instant.parse(n.asString()));
    }
}
```

## Step 7.6 — Dashboard query and cache

`service/DashboardCache.java`:

```java
package com.aravindakv.hiretrack.analytics.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** One Redis hash per user (field = timezone), so eviction is a single DEL without scanning keys. */
@Component
public class DashboardCache {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public DashboardCache(StringRedisTemplate redis, @Value("${hiretrack.dashboard.cache-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public Optional<String> get(long userId, String zone) {
        Object v = redis.opsForHash().get(key(userId), zone);
        return Optional.ofNullable((String) v);
    }

    public void put(long userId, String zone, String json) {
        redis.opsForHash().put(key(userId), zone, json);
        redis.expire(key(userId), ttl);
    }

    public void evict(long userId) {
        redis.delete(key(userId));
    }

    private static String key(long userId) {
        return "dashboard:" + userId;
    }
}
```

`service/DashboardService.java`:

```java
package com.aravindakv.hiretrack.analytics.service;

import com.aravindakv.hiretrack.analytics.domain.DashboardMath;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DashboardService {

    public record Dashboard(Map<String, Long> byStage, long interviewsThisWeek,
                            long sent, long replied, Double responseRate, String timezone) {}

    private static final String[] STAGES = {"WISHLIST", "APPLIED", "INTERVIEW", "OFFER", "REJECTED"};

    private final JdbcTemplate jdbc;
    private final DashboardCache cache;
    private final ObjectMapper json;
    private final Clock clock;

    public DashboardService(JdbcTemplate jdbc, DashboardCache cache, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.json = json;
        this.clock = clock;
    }

    public Dashboard get(long userId, ZoneId zone) {
        var cached = cache.get(userId, zone.getId());
        if (cached.isPresent()) return read(cached.get());

        Map<String, Long> byStage = new LinkedHashMap<>();
        for (String s : STAGES) byStage.put(s, 0L);
        jdbc.query("SELECT stage, count(*) AS n FROM app_projection WHERE user_id = ? GROUP BY stage",
                rs -> { byStage.put(rs.getString("stage"), rs.getLong("n")); }, userId);

        var week = DashboardMath.currentWeek(clock.instant(), zone);
        Long interviews = jdbc.queryForObject("""
                SELECT count(*) FROM interview_projection
                WHERE user_id = ? AND scheduled_at >= ? AND scheduled_at < ?""",
                Long.class, userId, Timestamp.from(week.from()), Timestamp.from(week.to()));

        Map<String, Object> rates = jdbc.queryForMap("""
                SELECT count(applied_date) AS sent,
                       count(*) FILTER (WHERE applied_date IS NOT NULL AND responded_at IS NOT NULL) AS replied
                FROM app_projection WHERE user_id = ?""", userId);
        long sent = ((Number) rates.get("sent")).longValue();
        long replied = ((Number) rates.get("replied")).longValue();

        Dashboard d = new Dashboard(byStage, interviews == null ? 0 : interviews, sent, replied,
                DashboardMath.responseRate(sent, replied), zone.getId());
        cache.put(userId, zone.getId(), write(d));
        return d;
    }

    private String write(Dashboard d) {
        try { return json.writeValueAsString(d); } catch (JacksonException e) { throw new IllegalStateException(e); }
    }

    private Dashboard read(String s) {
        try { return json.readValue(s, Dashboard.class); } catch (JacksonException e) { throw new IllegalStateException(e); }
    }
}
```

`web/DashboardController.java`:

```java
package com.aravindakv.hiretrack.analytics.web;

import com.aravindakv.hiretrack.analytics.service.DashboardService;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.ZoneId;

@RestController
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    DashboardService.Dashboard dashboard(@CurrentUser long userId, @RequestParam(defaultValue = "UTC") String tz) {
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "tz must be an IANA timezone such as Asia/Kolkata.");
        }
        return service.get(userId, zone);
    }
}
```

## Step 7.7 — Run it on its own: publish events by hand

```bash
tools/run-service.sh analytics-service
```

`tools/publish-sample-events.sh` writes events exactly as application-service would:

```bash
#!/usr/bin/env bash
# Usage: tools/publish-sample-events.sh <userId>
set -euo pipefail
U="${1:-900}"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TODAY=$(date -u +%Y-%m-%d)
IN2DAYS=$(date -u -d '+2 days' +%Y-%m-%dT10:00:00Z 2>/dev/null || date -u -v+2d +%Y-%m-%dT10:00:00Z)
ev() {  # type aggregateId version payload
  printf '%s|{"eventId":"%s","type":"%s","schemaVersion":1,"userId":%s,"aggregateId":"%s","aggregateVersion":%s,"occurredAt":"%s","payload":%s}\n' \
    "$U" "$(uuidgen)" "$1" "$U" "$2" "$3" "$NOW" "$4"
}
{
  ev application.created       application-1 0 '{"applicationId":1,"stage":"WISHLIST","appliedDate":null,"respondedAt":null}'
  ev application.stage-changed application-1 1 "{\"applicationId\":1,\"stage\":\"APPLIED\",\"appliedDate\":\"$TODAY\",\"respondedAt\":null}"
  ev application.stage-changed application-1 2 "{\"applicationId\":1,\"stage\":\"INTERVIEW\",\"appliedDate\":\"$TODAY\",\"respondedAt\":\"$NOW\"}"
  ev application.created       application-2 0 '{"applicationId":2,"stage":"WISHLIST","appliedDate":null,"respondedAt":null}'
  ev application.stage-changed application-2 1 "{\"applicationId\":2,\"stage\":\"APPLIED\",\"appliedDate\":\"$TODAY\",\"respondedAt\":null}"
  ev interview.scheduled       interview-1   0 "{\"interviewId\":1,\"applicationId\":1,\"scheduledAt\":\"$IN2DAYS\",\"round\":\"HR\"}"
} | docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic application-events --property parse.key=true --property key.separator='|'
echo "published"
```

**Verify**

```bash
chmod +x tools/publish-sample-events.sh && tools/publish-sample-events.sh 900
sleep 2
curl -s "localhost:8087/dashboard?tz=Asia/Kolkata" -H "Authorization: Bearer $(tools/mint-jwt.sh 900)" | jq
```

Expected:

```json
{
  "byStage": { "WISHLIST": 0, "APPLIED": 1, "INTERVIEW": 1, "OFFER": 0, "REJECTED": 0 },
  "interviewsThisWeek": 1,
  "sent": 2,
  "replied": 1,
  "responseRate": 0.5,
  "timezone": "Asia/Kolkata"
}
```

`interviewsThisWeek` is `0` if "in two days" falls in next week, for example when you run this on a Saturday.

Run the script **again**. It sends the same changes with new event IDs and the same versions. The dashboard doesn't change, because older-or-equal versions are ignored.

## Step 7.8 — Integration tests

`src/test/java/com/aravindakv/hiretrack/analytics/AnalyticsIT.java`:

```java
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
```

**Verify**

```bash
./gradlew :services:analytics-service:test
```

Expected: 11 tests pass (4 unit, 7 integration).

## Step 7.9 — Rebuilding the projection

If a bug ever corrupts the projections, rebuild them from the event history:

```bash
# 1. Stop analytics-service.
# 2. Clear the read model:
docker compose -f deploy/docker-compose.yml exec -T postgres psql "postgresql://analytics_svc:analytics_pw@localhost/hiretrack" \
  -c "TRUNCATE app_projection, interview_projection, deleted_aggregates, processed_events"
# 3. Rewind the consumer group:
docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group analytics --topic application-events --reset-offsets --to-earliest --execute
# 4. Start analytics-service. It replays every event.
```

This works only while Kafka still holds the full history. Topic retention for `application-events` is set to "keep forever" (locally in Phase 0's Kafka settings, on AWS in Phase 13) for exactly this reason.

---

## Phase demo

Run application-service (Phase 4) and analytics-service together, then `tools/demo-phase7.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
TOKEN=$(tools/mint-jwt.sh 950)
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
ID=$(curl -s -X POST localhost:8085/applications "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"company":"Hooli","role":"SRE"}' | jq .id)
ETAG=$(curl -s -D - -o /dev/null localhost:8085/applications/$ID "${H[@]}" | awk 'tolower($1)=="etag:"{print $2}' | tr -d '\r')
curl -s -o /dev/null -X PATCH localhost:8085/applications/$ID "${H[@]}" -H "If-Match: $ETAG" -d '{"stage":"APPLIED"}'
START=$(date +%s%N)
until [ "$(curl -s "localhost:8087/dashboard?tz=UTC" "${H[@]}" | jq '.byStage.APPLIED')" = "1" ]; do sleep 0.2; done
echo "dashboard updated in $(( ($(date +%s%N) - START) / 1000000 )) ms"
curl -s "localhost:8087/dashboard?tz=UTC" "${H[@]}" | jq -c .
```

Expected: `dashboard updated in <a few hundred> ms`, well inside the 5-second freshness SLO (spec 10.2), then `{"byStage":{"WISHLIST":0,"APPLIED":1,...},"sent":1,"replied":0,"responseRate":0.0,...}`.

## Done when

- [ ] 11 tests pass
- [ ] Duplicates, out-of-order events and late events after a delete leave the projection correct
- [ ] Interviews-this-week uses the viewer's timezone
- [ ] The dashboard reflects a change within 5 seconds
- [ ] The rebuild procedure works
- [ ] Commit: `git commit -am "Phase 7: analytics-service"`
