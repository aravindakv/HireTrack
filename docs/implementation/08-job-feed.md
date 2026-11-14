# Phase 8 — Job Feed Service

**Goal:** the Daily Job Feed (spec 3.6), built to scale:

| Part | What it does |
| --- | --- |
| **Planner** | One pod at a time (ShedLock). Every 5 minutes it creates a run for each due user. Users are due at 07:00 in their own timezone, plus a per-user jitter, and only if they were active in the last 14 days |
| **Workers** | Scale out on Kafka. They fetch postings from Adzuna, sharing one fetch between all users with the same search. Then they store new postings, skip ones already seen and keep the top 15 by word overlap |
| **Batch submitter / poller** | Send runs to ai-service's Batch API and apply the scores when they come back |
| **API** | Search profile, ranked feed, Refresh now (once an hour), Save to pipeline, Dismiss |

Every step is idempotent: a crash or redelivery anywhere gives the same feed, with no duplicate charges.

**Time:** 3–4 days.

**Runs on its own with:** Docker Compose infrastructure, a minted token, and WireMock standing in for Adzuna, resume-service, ai-service and application-service.

---

## Step 8.1 — Module and build file

Add to `settings.gradle.kts`:

```kotlin
include("services:job-feed-service")
```

`services/job-feed-service/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:common-events"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:idempotency"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
}
```

`src/main/java/com/aravindakv/hiretrack/feed/JobFeedApplication.java`:

```java
package com.aravindakv.hiretrack.feed;

import com.aravindakv.hiretrack.common.web.EnableHiretrackSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableHiretrackSecurity
@ConfigurationPropertiesScan
@EnableScheduling
public class JobFeedApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobFeedApplication.class, args);
    }
}
```

## Step 8.2 — Configuration

`application.yml`: the Phase 1 template with `<service>` = `job-feed-service`, `<schema>` = `feed`, `<port>` = `8086`, and all four flyway locations (`db/migration`, `outbox`, `events`, `idempotency`). Add:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  kafka:
    consumer:
      group-id: job-feed

hiretrack:
  feed:
    schedulers-enabled: ${FEED_SCHEDULERS_ENABLED:true}   # feature flag (Phase 14 launch plan)
    run-hour: 7                                    # 07:00 local
    jitter-minutes: 60
    active-days: 14
    max-results: 50
    max-remote-results: 25
    max-days-old: 7
    top-n: 15
    manual-refresh-cooldown: 1h
    submit-batch-size: 500
    max-score-attempts: 3
    query-cache-ttl: 6h
    retention-days: 30
  adzuna:
    base-url: ${ADZUNA_BASE_URL:https://api.adzuna.com}
    app-id: ${ADZUNA_APP_ID:}
    app-key: ${ADZUNA_APP_KEY:}
    timeout: 10s
  clients:
    resume-url: ${RESUME_SERVICE_URL:http://localhost:8082}
    ai-url: ${AI_SERVICE_URL:http://localhost:8083}
    application-url: ${APPLICATION_SERVICE_URL:http://localhost:8085}
```

`src/test/resources/application-test.yml`: the Phase 1 test template, plus:

```yaml
hiretrack:
  feed:
    schedulers-enabled: false        # tests call planner/submitter/poller directly
  adzuna:
    app-id: test-id
    app-key: test-key
```

`config/FeedProperties.java`:

```java
package com.aravindakv.hiretrack.feed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("hiretrack")
public record FeedProperties(Feed feed, Adzuna adzuna) {

    public record Feed(boolean schedulersEnabled, int runHour, int jitterMinutes, int activeDays, int maxResults,
                       int maxRemoteResults, int maxDaysOld, int topN, Duration manualRefreshCooldown,
                       int submitBatchSize, int maxScoreAttempts, Duration queryCacheTtl, int retentionDays) {}

    public record Adzuna(String baseUrl, String appId, String appKey, Duration timeout) {}
}
```

## Step 8.3 — Migrations

`db/migration/V1__feed.sql`:

```sql
CREATE TABLE search_profiles (
    user_id            BIGINT      PRIMARY KEY,
    keywords           TEXT        NOT NULL CHECK (length(keywords) BETWEEN 1 AND 100),
    country            TEXT        NOT NULL CHECK (country ~ '^[a-z]{2}$'),
    location           TEXT        NOT NULL CHECK (length(location) BETWEEN 1 AND 100),
    radius_km          INT         NOT NULL DEFAULT 25 CHECK (radius_km BETWEEN 5 AND 100),
    remote_ok          BOOLEAN     NOT NULL DEFAULT false,
    enabled            BOOLEAN     NOT NULL DEFAULT true,
    timezone           TEXT        NOT NULL,
    query_key          TEXT        NOT NULL,
    next_run_at        TIMESTAMPTZ NOT NULL,
    last_run_at        TIMESTAMPTZ,
    last_manual_run_at TIMESTAMPTZ,
    last_error         TEXT,
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX search_profiles_due ON search_profiles (next_run_at) WHERE enabled;

-- Fed by user.seen / user.registered from identity-service
CREATE TABLE user_activity (
    user_id      BIGINT PRIMARY KEY,
    last_seen_on DATE   NOT NULL
);

-- Shared catalog: one row per posting, whoever saw it first
CREATE TABLE postings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source        TEXT        NOT NULL,
    external_id   TEXT        NOT NULL,
    title         TEXT        NOT NULL,
    company       TEXT,
    location      TEXT,
    url           TEXT        NOT NULL,
    snippet       TEXT        NOT NULL,
    salary_min    INT,
    salary_max    INT,
    posted_at     TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);

CREATE TABLE feed_runs (
    id            UUID        PRIMARY KEY,
    run_key       TEXT        NOT NULL UNIQUE,  -- sched:<user>:<date> or manual:<user>:<yyyy-mm-ddThh>
    user_id       BIGINT      NOT NULL,
    trigger       TEXT        NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    status        TEXT        NOT NULL CHECK (status IN
                  ('REQUESTED', 'READY_TO_SCORE', 'SUBMITTING', 'SCORING', 'DONE', 'NOTHING_NEW', 'FAILED')),
    resume_id     BIGINT,
    resume_text   TEXT,                         -- snapshot for scoring; cleared when the run ends
    submission_id TEXT,
    ai_batch_id   TEXT,
    attempts      INT         NOT NULL DEFAULT 0,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX feed_runs_status ON feed_runs (status, updated_at);
CREATE INDEX feed_runs_user ON feed_runs (user_id, created_at DESC);

-- Per-user view of a posting. The PK makes "already seen" a cheap ON CONFLICT check.
CREATE TABLE feed_items (
    user_id         BIGINT      NOT NULL,
    posting_id      BIGINT      NOT NULL REFERENCES postings(id),
    run_id          UUID        NOT NULL,
    resume_id       BIGINT      NOT NULL,
    prefilter_score INT         NOT NULL DEFAULT 0,
    fit_score       INT         CHECK (fit_score BETWEEN 0 AND 100),
    missing_skills  JSONB,
    reason          TEXT,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'FILTERED', 'NEW', 'SAVED', 'DISMISSED')),
    application_id  BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, posting_id)
);
CREATE INDEX feed_items_feed ON feed_items (user_id, status, fit_score DESC);
CREATE INDEX feed_items_run ON feed_items (run_id);

CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

`FILTERED` items lost the top-15 cut. They stay in the table so they're never treated as new again.

## Step 8.4 — Pure helpers (unit-tested first)

`domain/QueryKey.java`:

```java
package com.aravindakv.hiretrack.feed.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

/** Users with the same normalised search share one Adzuna fetch. */
public final class QueryKey {

    private QueryKey() {}

    public static String of(String country, String location, int radiusKm, String keywords, boolean remoteOk) {
        String normalisedKeywords = Arrays.stream(keywords.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(w -> !w.isBlank()).sorted().collect(Collectors.joining(" "));
        String raw = country.toLowerCase(Locale.ROOT) + "|" + location.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                + "|" + radiusKm + "|" + normalisedKeywords + "|" + remoteOk;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`domain/RunSchedule.java`:

```java
package com.aravindakv.hiretrack.feed.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class RunSchedule {

    private RunSchedule() {}

    /**
     * The next run strictly after `now`: runHour:00 local time plus a stable per-user jitter,
     * so users spread across the hour instead of arriving in one spike.
     */
    public static Instant next(Instant now, ZoneId zone, int runHour, long userId, int jitterMinutes) {
        int jitter = (int) Math.floorMod(Long.hashCode(userId * 2654435761L), (long) Math.max(1, jitterMinutes));
        LocalDate today = now.atZone(zone).toLocalDate();
        ZonedDateTime candidate = today.atTime(LocalTime.of(runHour, 0)).plusMinutes(jitter).atZone(zone);
        if (!candidate.toInstant().isAfter(now)) {
            candidate = today.plusDays(1).atTime(LocalTime.of(runHour, 0)).plusMinutes(jitter).atZone(zone);
        }
        return candidate.toInstant();
    }
}
```

`domain/Prefilter.java`:

```java
package com.aravindakv.hiretrack.feed.domain;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Spec 3.6 step 3: rank by how many distinct words a posting shares with the resume. No AI, no cost. */
public final class Prefilter {

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "you", "our", "are", "will", "your",
            "this", "that", "have", "from", "has", "who", "all", "can", "job", "role", "work", "team", "years", "year");

    private Prefilter() {}

    public record Candidate(long postingId, String text, long postedEpoch) {}

    public record Ranked(long postingId, int score) {}

    public static Set<String> words(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+"))
                .map(w -> w.replaceAll("^\\.+|\\.+$", ""))
                .filter(w -> w.length() >= 3 && !STOP.contains(w))
                .collect(Collectors.toSet());
    }

    /** Highest overlap first; newer postings win ties. */
    public static List<Ranked> top(List<Candidate> candidates, String resume, int n) {
        Set<String> resumeWords = words(resume);
        return candidates.stream()
                .map(c -> {
                    Set<String> w = words(c.text());
                    w.retainAll(resumeWords);
                    return new Object[]{c, w.size()};
                })
                .sorted(Comparator.<Object[]>comparingInt(o -> -(int) o[1])
                        .thenComparingLong(o -> -((Candidate) o[0]).postedEpoch()))
                .limit(n)
                .map(o -> new Ranked(((Candidate) o[0]).postingId(), (int) o[1]))
                .toList();
    }
}
```

`src/test/java/com/aravindakv/hiretrack/feed/domain/FeedDomainTest.java`:

```java
package com.aravindakv.hiretrack.feed.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedDomainTest {

    @Test
    void queryKeyIgnoresCaseWordOrderAndExtraSpaces() {
        assertThat(QueryKey.of("in", "Bangalore", 25, "Java  Developer", false))
                .isEqualTo(QueryKey.of("IN", " bangalore ", 25, "developer java", false));
        assertThat(QueryKey.of("in", "Bangalore", 25, "java", false))
                .isNotEqualTo(QueryKey.of("in", "Bangalore", 50, "java", false));
    }

    @Test
    void nextRunIsSevenLocalPlusJitterAndAlwaysInTheFuture() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        Instant beforeSeven = Instant.parse("2026-11-18T00:00:00Z");     // 05:30 IST
        Instant next = RunSchedule.next(beforeSeven, kolkata, 7, 42L, 60);
        var local = next.atZone(kolkata);
        assertThat(local.toLocalDate().toString()).isEqualTo("2026-11-18");
        assertThat(local.getHour()).isEqualTo(7);

        Instant afterRun = next.plusSeconds(1);
        assertThat(RunSchedule.next(afterRun, kolkata, 7, 42L, 60).atZone(kolkata).toLocalDate().toString())
                .isEqualTo("2026-11-19");
    }

    @Test
    void jitterIsStablePerUserAndSpreadsUsers() {
        ZoneId utc = ZoneId.of("UTC");
        Instant now = Instant.parse("2026-11-18T00:00:00Z");
        assertThat(RunSchedule.next(now, utc, 7, 1L, 60)).isEqualTo(RunSchedule.next(now, utc, 7, 1L, 60));
        long distinct = java.util.stream.LongStream.range(1, 200)
                .mapToObj(u -> RunSchedule.next(now, utc, 7, u, 60)).distinct().count();
        assertThat(distinct).isGreaterThan(30);
    }

    @Test
    void prefilterRanksBySharedWordsThenRecency() {
        var ranked = Prefilter.top(List.of(
                new Prefilter.Candidate(1, "Senior Java Spring Kafka engineer", 100),
                new Prefilter.Candidate(2, "Graphic designer, Photoshop", 300),
                new Prefilter.Candidate(3, "Java developer", 200),
                new Prefilter.Candidate(4, "Java developer", 250)),
                "Java, Spring Boot, Kafka, PostgreSQL", 3);
        assertThat(ranked).extracting(Prefilter.Ranked::postingId).containsExactly(1L, 4L, 3L);
        assertThat(ranked.get(0).score()).isEqualTo(3);
    }
}
```

**Verify**

```bash
./gradlew :services:job-feed-service:test --tests '*FeedDomainTest'
```

Expected: 4 tests pass.

## Step 8.5 — Search profile (entity, repository, API)

`domain/SearchProfile.java`:

```java
package com.aravindakv.hiretrack.feed.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "search_profiles")
public class SearchProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false) private String keywords;
    @Column(nullable = false) private String country;
    @Column(nullable = false) private String location;
    @Column(name = "radius_km", nullable = false) private int radiusKm;
    @Column(name = "remote_ok", nullable = false) private boolean remoteOk;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false) private String timezone;
    @Column(name = "query_key", nullable = false) private String queryKey;
    @Column(name = "next_run_at", nullable = false) private Instant nextRunAt;
    @Column(name = "last_run_at") private Instant lastRunAt;
    @Column(name = "last_manual_run_at") private Instant lastManualRunAt;
    @Column(name = "last_error") private String lastError;
    @Version private Long version;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected SearchProfile() {}

    public SearchProfile(Long userId) {
        this.userId = userId;
    }

    public void update(String keywords, String country, String location, int radiusKm, boolean remoteOk,
                       boolean enabled, String timezone, Instant nextRunAt) {
        this.keywords = keywords.trim();
        this.country = country.toLowerCase();
        this.location = location.trim();
        this.radiusKm = radiusKm;
        this.remoteOk = remoteOk;
        this.enabled = enabled;
        this.timezone = timezone;
        this.queryKey = QueryKey.of(country, location, radiusKm, keywords, remoteOk);
        this.nextRunAt = nextRunAt;
    }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public Long getUserId() { return userId; }
    public String getKeywords() { return keywords; }
    public String getCountry() { return country; }
    public String getLocation() { return location; }
    public int getRadiusKm() { return radiusKm; }
    public boolean isRemoteOk() { return remoteOk; }
    public boolean isEnabled() { return enabled; }
    public String getTimezone() { return timezone; }
    public String getQueryKey() { return queryKey; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public Instant getLastManualRunAt() { return lastManualRunAt; }
    public String getLastError() { return lastError; }
}
```

`persistence/SearchProfileRepository.java`:

```java
package com.aravindakv.hiretrack.feed.persistence;

import com.aravindakv.hiretrack.feed.domain.SearchProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchProfileRepository extends JpaRepository<SearchProfile, Long> {}
```

`web/ProfileController.java`:

```java
package com.aravindakv.hiretrack.feed.web;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.domain.RunSchedule;
import com.aravindakv.hiretrack.feed.domain.SearchProfile;
import com.aravindakv.hiretrack.feed.persistence.SearchProfileRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

@RestController
public class ProfileController {

    public record ProfileRequest(
            @NotBlank @Size(max = 100) String keywords,
            @NotBlank @Pattern(regexp = "^[a-zA-Z]{2}$") String country,
            @NotBlank @Size(max = 100) String location,
            @Min(5) @Max(100) int radiusKm,
            boolean remoteOk,
            boolean enabled,
            @NotBlank String timezone) {}

    public record ProfileResponse(String keywords, String country, String location, int radiusKm, boolean remoteOk,
                                  boolean enabled, String timezone, Instant nextRunAt, Instant lastRunAt, String lastError) {
        static ProfileResponse of(SearchProfile p) {
            return new ProfileResponse(p.getKeywords(), p.getCountry(), p.getLocation(), p.getRadiusKm(), p.isRemoteOk(),
                    p.isEnabled(), p.getTimezone(), p.getNextRunAt(), p.getLastRunAt(), p.getLastError());
        }
    }

    private final SearchProfileRepository profiles;
    private final FeedProperties props;
    private final Clock clock;

    public ProfileController(SearchProfileRepository profiles, FeedProperties props, Clock clock) {
        this.profiles = profiles;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/search-profile")
    @Transactional(readOnly = true)
    ProfileResponse get(@CurrentUser long userId) {
        return profiles.findById(userId).map(ProfileResponse::of)
                .orElseThrow(() -> ApiException.notFound("Search profile"));
    }

    /** PUT is naturally idempotent: the same body twice gives the same profile. */
    @PutMapping("/search-profile")
    @Transactional
    ProfileResponse put(@CurrentUser long userId, @Valid @RequestBody ProfileRequest req) {
        ZoneId zone;
        try {
            zone = ZoneId.of(req.timezone());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "timezone must be an IANA zone such as Asia/Kolkata.");
        }
        SearchProfile p = profiles.findById(userId).orElseGet(() -> new SearchProfile(userId));
        p.update(req.keywords(), req.country(), req.location(), req.radiusKm(), req.remoteOk(), req.enabled(),
                zone.getId(), RunSchedule.next(clock.instant(), zone, props.feed().runHour(), userId, props.feed().jitterMinutes()));
        return ProfileResponse.of(profiles.save(p));
    }
}
```

## Step 8.6 — User activity from identity events

`messaging/UserActivityListener.java`:

```java
package com.aravindakv.hiretrack.feed.messaging;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.ProcessedEvents;
import com.aravindakv.hiretrack.common.events.Topics;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Spec 10.4 cost lever 1: only users seen in the last 14 days get a daily run. */
@Component
public class UserActivityListener {

    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final ObjectMapper json;

    public UserActivityListener(JdbcTemplate jdbc, ProcessedEvents processed, ObjectMapper json) {
        this.jdbc = jdbc;
        this.processed = processed;
        this.json = json;
    }

    @KafkaListener(topics = Topics.IDENTITY_EVENTS, groupId = "job-feed-activity")
    @Transactional
    public void on(String value) {
        EventEnvelope e = EventEnvelope.parse(value, json);
        if (!processed.markProcessed(e.eventId())) return;
        if (!EventTypes.USER_SEEN.equals(e.type()) && !EventTypes.USER_REGISTERED.equals(e.type())) return;
        LocalDate day = e.payload().hasNonNull("seenOn")
                ? LocalDate.parse(e.payload().get("seenOn").asString())
                : e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        jdbc.update("""
                INSERT INTO user_activity (user_id, last_seen_on) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET last_seen_on = GREATEST(user_activity.last_seen_on, EXCLUDED.last_seen_on)""",
                e.userId(), Date.valueOf(day));
    }
}
```

## Step 8.7 — Clients: Adzuna and the other services

`client/AdzunaClient.java`:

```java
package com.aravindakv.hiretrack.feed.client;

import com.aravindakv.hiretrack.feed.config.FeedProperties;
import tools.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Adzuna job search. Only search results are used; posting pages are never fetched. */
@Component
public class AdzunaClient {

    public record AdzunaPosting(String externalId, String title, String company, String location, String url,
                                String snippet, Integer salaryMin, Integer salaryMax, Instant postedAt) {}

    private final RestClient rest;
    private final FeedProperties.Adzuna cfg;
    private final MeterRegistry meters;

    public AdzunaClient(RestClient.Builder builder, FeedProperties props, MeterRegistry meters) {
        this.cfg = props.adzuna();
        this.meters = meters;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(cfg.timeout());
        this.rest = builder.clone().baseUrl(cfg.baseUrl()).requestFactory(factory).build();
    }

    /** Retries twice with backoff; throws after that so the run records the error. */
    public List<AdzunaPosting> search(String country, String what, String where, Integer distanceKm,
                                      int maxDaysOld, int resultsPerPage) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long start = System.nanoTime();
            try {
                JsonNode body = rest.get().uri(u -> {
                    var b = u.path("/v1/api/jobs/{country}/search/1")
                            .queryParam("app_id", cfg.appId())
                            .queryParam("app_key", cfg.appKey())
                            .queryParam("what", what)
                            .queryParam("max_days_old", maxDaysOld)
                            .queryParam("results_per_page", resultsPerPage)
                            .queryParam("sort_by", "date");
                    if (where != null) b.queryParam("where", where);
                    if (distanceKm != null) b.queryParam("distance", distanceKm);
                    return b.build(country);
                }).retrieve().body(JsonNode.class);
                record("ok", start);
                return parse(body);
            } catch (RuntimeException e) {
                record("error", start);
                last = e;
                sleep(500L * (attempt + 1));
            }
        }
        throw new IllegalStateException("Adzuna unavailable: " + last.getMessage(), last);
    }

    private static List<AdzunaPosting> parse(JsonNode body) {
        List<AdzunaPosting> out = new ArrayList<>();
        if (body == null) return out;
        for (JsonNode r : body.path("results")) {
            String id = r.path("id").asString(null);
            String url = r.path("redirect_url").asString(null);
            if (id == null || url == null) continue;
            out.add(new AdzunaPosting(id, r.path("title").asString("").trim(),
                    r.path("company").path("display_name").asString(null),
                    r.path("location").path("display_name").asString(null), url,
                    r.path("description").asString("").trim(),
                    r.hasNonNull("salary_min") ? r.get("salary_min").asInt() : null,
                    r.hasNonNull("salary_max") ? r.get("salary_max").asInt() : null,
                    r.hasNonNull("created") ? Instant.parse(r.get("created").asString()) : null));
        }
        return out;
    }

    private void record(String outcome, long startNanos) {
        meters.counter("hiretrack.adzuna.requests", "outcome", outcome).increment();
        meters.timer("hiretrack.adzuna.latency").record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

`client/ServiceClients.java`:

```java
package com.aravindakv.hiretrack.feed.client;

import com.aravindakv.hiretrack.common.web.InternalRestClients;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ServiceClients {

    public record ActiveResume(long resumeId, String content) {}

    public record ScoreItem(String customId, long userId, String resumeText, List<Map<String, String>> postings) {}

    private final RestClient resume;
    private final RestClient ai;
    private final RestClient application;

    public ServiceClients(RestClient.Builder builder, ObjectMapper json, @Value("${hiretrack.internal.token}") String token,
                          @Value("${hiretrack.clients.resume-url}") String resumeUrl,
                          @Value("${hiretrack.clients.ai-url}") String aiUrl,
                          @Value("${hiretrack.clients.application-url}") String applicationUrl) {
        this.resume = InternalRestClients.create(builder, resumeUrl, token, Duration.ofSeconds(3), json);
        this.ai = InternalRestClients.create(builder, aiUrl, token, Duration.ofSeconds(120), json);
        this.application = InternalRestClients.create(builder, applicationUrl, token, Duration.ofSeconds(5), json);
    }

    public ActiveResume activeResume(long userId) {
        return resume.get().uri("/internal/users/{id}/active-resume", userId).retrieve().body(ActiveResume.class);
    }

    public String submitBatch(String submissionId, List<ScoreItem> items) {
        JsonNode res = ai.post().uri("/internal/feed-batches")
                .body(Map.of("submissionId", submissionId, "items", items)).retrieve().body(JsonNode.class);
        return res.get("batchId").asString();
    }

    public JsonNode batch(String batchId) {
        return ai.get().uri("/internal/feed-batches/{id}", batchId).retrieve().body(JsonNode.class);
    }

    public long createApplication(long userId, String idempotencyKey, Map<String, Object> body) {
        JsonNode res = application.post().uri("/internal/users/{id}/applications", userId)
                .header("Idempotency-Key", idempotencyKey).body(body).retrieve().body(JsonNode.class);
        return res.get("id").asLong();
    }
}
```

## Step 8.8 — Shared Adzuna fetch (Redis cache + lock)

`service/PostingFetcher.java`:

```java
package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.feed.client.AdzunaClient;
import com.aravindakv.hiretrack.feed.client.AdzunaClient.AdzunaPosting;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.domain.SearchProfile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Adzuna fetch per query key per hour, shared by every user with the same search.
 * A short Redis lock stops a thundering herd: other workers wait for the cached result.
 */
@Component
public class PostingFetcher {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final AdzunaClient adzuna;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final FeedProperties.Feed props;
    private final MeterRegistry meters;
    private final Clock clock;

    public PostingFetcher(AdzunaClient adzuna, StringRedisTemplate redis, ObjectMapper json, FeedProperties props,
                          MeterRegistry meters, Clock clock) {
        this.adzuna = adzuna;
        this.redis = redis;
        this.json = json;
        this.props = props.feed();
        this.meters = meters;
        this.clock = clock;
    }

    public List<AdzunaPosting> fetch(SearchProfile p) {
        String cacheKey = "adzuna:" + p.getQueryKey() + ":" + HOUR.format(clock.instant());
        String lockKey = cacheKey + ":lock";
        for (int i = 0; i < 40; i++) {                            // up to ~10 s waiting for another worker
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                meters.counter("hiretrack.feed.query.cache.hits", "result", "hit").increment();
                return read(cached);
            }
            Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
            if (Boolean.TRUE.equals(locked)) {
                try {
                    meters.counter("hiretrack.feed.query.cache.hits", "result", "miss").increment();
                    List<AdzunaPosting> fresh = fetchFromAdzuna(p);
                    redis.opsForValue().set(cacheKey, write(fresh), props.queryCacheTtl());
                    return fresh;
                } finally {
                    redis.delete(lockKey);
                }
            }
            sleep(250);
        }
        return fetchFromAdzuna(p);                                // lock holder died: fetch ourselves
    }

    private List<AdzunaPosting> fetchFromAdzuna(SearchProfile p) {
        Map<String, AdzunaPosting> byId = new LinkedHashMap<>();
        adzuna.search(p.getCountry(), p.getKeywords(), p.getLocation(), p.getRadiusKm(),
                props.maxDaysOld(), props.maxResults()).forEach(a -> byId.put(a.externalId(), a));
        if (p.isRemoteOk()) {
            adzuna.search(p.getCountry(), p.getKeywords() + " remote", null, null,
                    props.maxDaysOld(), props.maxRemoteResults()).forEach(a -> byId.putIfAbsent(a.externalId(), a));
        }
        meters.counter("hiretrack.feed.postings", "stage", "fetched").increment(byId.size());
        return new ArrayList<>(byId.values());
    }

    private String write(List<AdzunaPosting> postings) {
        try { return json.writeValueAsString(postings); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private List<AdzunaPosting> read(String s) {
        try { return json.readValue(s, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

## Step 8.9 — The pipeline: planner, worker, submitter, poller

`service/FeedRuns.java` holds the shared SQL for run state changes:

```java
package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class FeedRuns {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public FeedRuns(JdbcTemplate jdbc, OutboxWriter outbox, ObjectMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
        this.meters = meters;
    }

    /** Creates a run and its command event, in the caller's transaction. Returns null if the run_key exists. */
    public UUID request(long userId, String runKey, String trigger) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO feed_runs (id, run_key, user_id, trigger, status) VALUES (?, ?, ?, ?, 'REQUESTED')
                ON CONFLICT (run_key) DO NOTHING""", id, runKey, userId, trigger);
        if (inserted == 0) return null;
        outbox.write(Topics.FEED_COMMANDS, EventEnvelope.create(EventTypes.FEED_RUN_REQUESTED, userId,
                "feed-run-" + id, 0, Map.of("runId", id.toString(), "trigger", trigger), json));
        return id;
    }

    public void finish(UUID runId, long userId, String status, String error, String trigger) {
        jdbc.update("""
                UPDATE feed_runs SET status = ?, error = ?, resume_text = NULL, updated_at = now() WHERE id = ?""",
                status, error, runId);
        jdbc.update("UPDATE search_profiles SET last_run_at = now(), last_error = ? WHERE user_id = ?", error, userId);
        meters.counter("hiretrack.feed.runs", "trigger", trigger, "status", status).increment();
    }

    public void publishReady(UUID runId, long userId, int newItems) {
        outbox.write(Topics.FEED_EVENTS, EventEnvelope.create(EventTypes.FEED_READY, userId, "feed-run-" + runId, 0,
                Map.of("runId", runId.toString(), "newItems", newItems), json));
    }
}
```

`service/FeedPlanner.java`:

```java
package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.domain.RunSchedule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class FeedPlanner {

    private final JdbcTemplate jdbc;
    private final FeedRuns runs;
    private final FeedProperties.Feed props;
    private final Clock clock;

    public FeedPlanner(JdbcTemplate jdbc, FeedRuns runs, FeedProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.props = props.feed();
        this.clock = clock;
    }

    record Due(long userId, String timezone) {}

    /** Creates today's scheduled run for every due, active user. Running it twice changes nothing. */
    @Transactional
    public int plan() {
        Instant now = clock.instant();
        List<Due> due = jdbc.query("""
                SELECT p.user_id, p.timezone FROM search_profiles p
                JOIN user_activity a ON a.user_id = p.user_id
                WHERE p.enabled AND p.next_run_at <= ? AND a.last_seen_on >= ?
                ORDER BY p.next_run_at
                LIMIT 1000
                FOR UPDATE OF p SKIP LOCKED""",
                (rs, i) -> new Due(rs.getLong("user_id"), rs.getString("timezone")),
                Timestamp.from(now), java.sql.Date.valueOf(LocalDate.now(clock).minusDays(props.activeDays())));
        for (Due d : due) {
            ZoneId zone = ZoneId.of(d.timezone());
            runs.request(d.userId(), "sched:" + d.userId() + ":" + now.atZone(zone).toLocalDate(), "SCHEDULED");
            jdbc.update("UPDATE search_profiles SET next_run_at = ? WHERE user_id = ?",
                    Timestamp.from(RunSchedule.next(now, zone, props.runHour(), d.userId(), props.jitterMinutes())), d.userId());
        }
        // Inactive users keep moving their next_run_at forward so they don't pile up as "due".
        jdbc.update("""
                UPDATE search_profiles SET next_run_at = next_run_at + interval '1 day'
                WHERE enabled AND next_run_at <= ? AND user_id NOT IN
                      (SELECT user_id FROM user_activity WHERE last_seen_on >= ?)""",
                Timestamp.from(now), java.sql.Date.valueOf(LocalDate.now(clock).minusDays(props.activeDays())));
        return due.size();
    }
}
```

`service/FeedWorker.java`:

```java
package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.ProcessedEvents;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.feed.client.AdzunaClient.AdzunaPosting;
import com.aravindakv.hiretrack.feed.client.ServiceClients;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.domain.Prefilter;
import com.aravindakv.hiretrack.feed.domain.SearchProfile;
import com.aravindakv.hiretrack.feed.persistence.SearchProfileRepository;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes feed.run-requested. External calls (resume-service, Adzuna) happen outside any DB transaction;
 * all writes for the run then happen in one transaction. A redelivered command finds the run already
 * past REQUESTED and does nothing.
 */
@Component
public class FeedWorker {

    private static final Logger log = LoggerFactory.getLogger(FeedWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ProcessedEvents processed;
    private final SearchProfileRepository profiles;
    private final ServiceClients clients;
    private final PostingFetcher fetcher;
    private final FeedRuns runs;
    private final FeedProperties.Feed props;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public FeedWorker(JdbcTemplate jdbc, TransactionTemplate tx, ProcessedEvents processed, SearchProfileRepository profiles,
                      ServiceClients clients, PostingFetcher fetcher, FeedRuns runs, FeedProperties props,
                      ObjectMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.processed = processed;
        this.profiles = profiles;
        this.clients = clients;
        this.fetcher = fetcher;
        this.runs = runs;
        this.props = props.feed();
        this.json = json;
        this.meters = meters;
    }

    record Run(UUID id, long userId, String trigger, String status) {}

    @KafkaListener(topics = Topics.FEED_COMMANDS, groupId = "job-feed-workers")
    public void on(String value) {
        EventEnvelope e = EventEnvelope.parse(value, json);
        UUID runId = UUID.fromString(e.payload().get("runId").asString());
        Run run = jdbc.query("SELECT id, user_id, trigger, status FROM feed_runs WHERE id = ?",
                rs -> rs.next() ? new Run(runId, rs.getLong("user_id"), rs.getString("trigger"), rs.getString("status")) : null,
                runId);
        if (run == null || !"REQUESTED".equals(run.status())) return;     // already handled

        SearchProfile profile = profiles.findById(run.userId()).orElse(null);
        if (profile == null) {
            tx.executeWithoutResult(s -> runs.finish(runId, run.userId(), "FAILED", "No search profile.", run.trigger()));
            return;
        }
        ServiceClients.ActiveResume resume;
        List<AdzunaPosting> fetched;
        try {
            resume = clients.activeResume(run.userId());
            fetched = fetcher.fetch(profile);
        } catch (ApiException ex) {
            String msg = ex.status() == 404 ? "Add a resume to get job matches." : "Couldn't load your resume. Will retry tomorrow.";
            tx.executeWithoutResult(s -> runs.finish(runId, run.userId(), "FAILED", msg, run.trigger()));
            return;
        } catch (RuntimeException ex) {
            log.warn("Feed run {} failed: {}", runId, ex.getMessage());
            tx.executeWithoutResult(s -> runs.finish(runId, run.userId(), "FAILED",
                    "Job search is unavailable right now. Will retry tomorrow.", run.trigger()));
            return;
        }

        tx.executeWithoutResult(s -> {
            if (!processed.markProcessed(e.eventId())) return;
            List<Prefilter.Candidate> fresh = new ArrayList<>();
            for (AdzunaPosting a : fetched) {
                Long postingId = upsertPosting(a);
                int inserted = jdbc.update("""
                        INSERT INTO feed_items (user_id, posting_id, run_id, resume_id) VALUES (?, ?, ?, ?)
                        ON CONFLICT (user_id, posting_id) DO NOTHING""",
                        run.userId(), postingId, runId, resume.resumeId());
                if (inserted == 1) {
                    fresh.add(new Prefilter.Candidate(postingId, a.title() + " " + a.snippet(),
                            a.postedAt() == null ? 0 : a.postedAt().getEpochSecond()));
                }
            }
            meters.counter("hiretrack.feed.postings", "stage", "new").increment(fresh.size());
            if (fresh.isEmpty()) {
                runs.finish(runId, run.userId(), "NOTHING_NEW", null, run.trigger());
                return;
            }
            List<Prefilter.Ranked> top = Prefilter.top(fresh, resume.content(), props.topN());
            for (Prefilter.Ranked r : top) {
                jdbc.update("UPDATE feed_items SET prefilter_score = ? WHERE user_id = ? AND posting_id = ?",
                        r.score(), run.userId(), r.postingId());
            }
            // Everything that lost the cut is FILTERED: never scored, never "new" again
            jdbc.update("""
                    UPDATE feed_items SET status = 'FILTERED'
                    WHERE run_id = ? AND status = 'PENDING' AND NOT (posting_id = ANY(?))""",
                    runId, top.stream().map(Prefilter.Ranked::postingId).toArray(Long[]::new));
            meters.counter("hiretrack.feed.postings", "stage", "prefiltered").increment(top.size());
            jdbc.update("""
                    UPDATE feed_runs SET status = 'READY_TO_SCORE', resume_id = ?, resume_text = ?, updated_at = now()
                    WHERE id = ? AND status = 'REQUESTED'""", resume.resumeId(), resume.content(), runId);
        });
    }

    private Long upsertPosting(AdzunaPosting a) {
        return jdbc.queryForObject("""
                INSERT INTO postings (source, external_id, title, company, location, url, snippet, salary_min, salary_max, posted_at)
                VALUES ('ADZUNA', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, external_id) DO UPDATE SET title = EXCLUDED.title
                RETURNING id""", Long.class,
                a.externalId(), a.title(), a.company(), a.location(), a.url(), a.snippet(),
                a.salaryMin(), a.salaryMax(), a.postedAt() == null ? null : Timestamp.from(a.postedAt()));
    }
}
```

`service/BatchSubmitter.java`:

```java
package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.feed.client.ServiceClients;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Groups READY_TO_SCORE runs into one ai-service batch. The submission ID is stored on the runs
 * BEFORE calling ai-service; if the call's answer is lost, the next attempt reuses the same
 * submission ID and ai-service returns the existing batch (no second charge).
 */
@Service
public class BatchSubmitter {

    private static final Logger log = LoggerFactory.getLogger(BatchSubmitter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ServiceClients clients;
    private final FeedProperties.Feed props;

    public BatchSubmitter(JdbcTemplate jdbc, TransactionTemplate tx, ServiceClients clients, FeedProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clients = clients;
        this.props = props.feed();
    }

    public void submit() {
        // Retry submissions whose answer was lost (stuck in SUBMITTING for 10+ minutes)
        List<String> stuck = jdbc.queryForList("""
                SELECT DISTINCT submission_id FROM feed_runs
                WHERE status = 'SUBMITTING' AND updated_at < now() - interval '10 minutes'""", String.class);
        stuck.forEach(this::send);

        String submissionId = UUID.randomUUID().toString();
        Integer claimed = tx.execute(s -> jdbc.update("""
                UPDATE feed_runs SET status = 'SUBMITTING', submission_id = ?, updated_at = now()
                WHERE id IN (SELECT id FROM feed_runs WHERE status = 'READY_TO_SCORE'
                             ORDER BY updated_at LIMIT ? FOR UPDATE SKIP LOCKED)""",
                submissionId, props.submitBatchSize()));
        if (claimed != null && claimed > 0) send(submissionId);
    }

    private void send(String submissionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, resume_text FROM feed_runs WHERE submission_id = ? AND status = 'SUBMITTING'", submissionId);
        if (rows.isEmpty()) return;
        List<ServiceClients.ScoreItem> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID runId = (UUID) r.get("id");
            List<Map<String, String>> postings = jdbc.query("""
                    SELECT p.id, p.title, coalesce(p.company, '') AS company, p.snippet
                    FROM feed_items i JOIN postings p ON p.id = i.posting_id
                    WHERE i.run_id = ? AND i.status = 'PENDING'""",
                    (rs, n) -> Map.of("postingId", Long.toString(rs.getLong("id")), "title", rs.getString("title"),
                            "company", rs.getString("company"), "snippet", rs.getString("snippet")),
                    runId);
            items.add(new ServiceClients.ScoreItem(runId.toString(), ((Number) r.get("user_id")).longValue(),
                    (String) r.get("resume_text"), postings));
        }
        try {
            String batchId = clients.submitBatch(submissionId, items);
            jdbc.update("""
                    UPDATE feed_runs SET status = 'SCORING', ai_batch_id = ?, updated_at = now()
                    WHERE submission_id = ? AND status = 'SUBMITTING'""", batchId, submissionId);
        } catch (RuntimeException e) {
            log.warn("Batch submission {} failed; will retry: {}", submissionId, e.getMessage());
        }
    }
}
```

`service/BatchPoller.java`:

```java
package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.feed.client.ServiceClients;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import tools.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BatchPoller {

    private static final Logger log = LoggerFactory.getLogger(BatchPoller.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ServiceClients clients;
    private final FeedRuns runs;
    private final FeedProperties.Feed props;
    private final MeterRegistry meters;

    public BatchPoller(JdbcTemplate jdbc, TransactionTemplate tx, ServiceClients clients, FeedRuns runs,
                       FeedProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clients = clients;
        this.runs = runs;
        this.props = props.feed();
        this.meters = meters;
    }

    public void poll() {
        List<String> batches = jdbc.queryForList(
                "SELECT DISTINCT ai_batch_id FROM feed_runs WHERE status = 'SCORING' LIMIT 50", String.class);
        for (String batchId : batches) {
            try {
                JsonNode view = clients.batch(batchId);
                String status = view.path("status").asString();
                if (!"ENDED".equals(status) && !"FAILED".equals(status)) continue;
                tx.executeWithoutResult(s -> view.path("items").forEach(this::apply));
            } catch (RuntimeException e) {
                log.warn("Polling ai batch {} failed: {}", batchId, e.getMessage());
            }
        }
    }

    /** Applying the same result twice is harmless: only PENDING items and SCORING runs are updated. */
    private void apply(JsonNode item) {
        UUID runId = UUID.fromString(item.path("customId").asString());
        Map<String, Object> run = jdbc.queryForList(
                "SELECT user_id, trigger, attempts, status FROM feed_runs WHERE id = ?", runId).stream().findFirst().orElse(null);
        if (run == null || !"SCORING".equals(run.get("status"))) return;
        long userId = ((Number) run.get("user_id")).longValue();
        String trigger = (String) run.get("trigger");

        if ("SUCCEEDED".equals(item.path("status").asString())) {
            int scored = 0;
            for (JsonNode s : item.path("scores")) {
                scored += jdbc.update("""
                        UPDATE feed_items SET fit_score = ?, missing_skills = ?::jsonb, reason = ?, status = 'NEW'
                        WHERE run_id = ? AND posting_id = ? AND status = 'PENDING'""",
                        s.path("fitScore").asInt(), s.path("missingSkills").toString(), s.path("reason").asString(),
                        runId, Long.parseLong(s.path("postingId").asString()));
            }
            jdbc.update("UPDATE feed_items SET status = 'FILTERED' WHERE run_id = ? AND status = 'PENDING'", runId);
            meters.counter("hiretrack.feed.postings", "stage", "scored").increment(scored);
            runs.finish(runId, userId, "DONE", null, trigger);
            runs.publishReady(runId, userId, scored);
        } else {
            int attempts = ((Number) run.get("attempts")).intValue() + 1;
            if (attempts < props.maxScoreAttempts()) {
                jdbc.update("""
                        UPDATE feed_runs SET status = 'READY_TO_SCORE', attempts = ?, submission_id = NULL,
                               ai_batch_id = NULL, updated_at = now() WHERE id = ?""", attempts, runId);
            } else {
                jdbc.update("UPDATE feed_items SET status = 'FILTERED' WHERE run_id = ? AND status = 'PENDING'", runId);
                runs.finish(runId, userId, "FAILED", "Scoring failed. Will retry tomorrow.", trigger);
            }
        }
    }
}
```

`service/FeedSchedulers.java`, the only place with timers, behind a feature flag:

```java
package com.aravindakv.hiretrack.feed.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hiretrack.feed.schedulers-enabled", havingValue = "true")
public class FeedSchedulers {

    private final FeedPlanner planner;
    private final BatchSubmitter submitter;
    private final BatchPoller poller;
    private final JdbcTemplate jdbc;

    public FeedSchedulers(FeedPlanner planner, BatchSubmitter submitter, BatchPoller poller, JdbcTemplate jdbc) {
        this.planner = planner;
        this.submitter = submitter;
        this.poller = poller;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "feed-planner", lockAtMostFor = "4m")
    public void plan() { planner.plan(); }

    @Scheduled(fixedDelayString = "${hiretrack.feed.submit-interval:2m}", initialDelayString = "30s")
    @SchedulerLock(name = "feed-submitter", lockAtMostFor = "5m")
    public void submit() { submitter.submit(); }

    @Scheduled(fixedDelayString = "${hiretrack.feed.poll-interval:1m}", initialDelayString = "${hiretrack.feed.poll-interval:1m}")
    @SchedulerLock(name = "feed-poller", lockAtMostFor = "5m")
    public void poll() { poller.poll(); }

    /** Spec 3.6: NEW and DISMISSED items older than 30 days go; SAVED stay. */
    @Scheduled(cron = "0 20 3 * * *")
    @SchedulerLock(name = "feed-cleanup", lockAtMostFor = "30m")
    public void cleanup() {
        jdbc.update("DELETE FROM feed_items WHERE status IN ('NEW', 'DISMISSED', 'FILTERED') AND created_at < now() - interval '30 days'");
        jdbc.update("""
                DELETE FROM postings p WHERE first_seen_at < now() - interval '30 days'
                AND NOT EXISTS (SELECT 1 FROM feed_items i WHERE i.posting_id = p.id)""");
        jdbc.update("DELETE FROM feed_runs WHERE created_at < now() - interval '90 days'");
    }
}
```

`config/FeedInfraConfig.java`:

```java
package com.aravindakv.hiretrack.feed.config;

import com.aravindakv.hiretrack.common.events.Topics;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class FeedInfraConfig {

    @Bean
    LockProvider lockProvider(JdbcTemplate jdbc) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).usingDbTime().build());
    }

    @Bean
    NewTopic feedCommands(@Value("${hiretrack.kafka.partitions}") int p, @Value("${hiretrack.kafka.replicas}") int r) {
        return TopicBuilder.name(Topics.FEED_COMMANDS).partitions(p).replicas(r).build();
    }

    @Bean
    NewTopic feedEvents(@Value("${hiretrack.kafka.partitions}") int p, @Value("${hiretrack.kafka.replicas}") int r) {
        return TopicBuilder.name(Topics.FEED_EVENTS).partitions(p).replicas(r).build();
    }
}
```

## Step 8.10 — Feed API

`web/JobsController.java`:

```java
package com.aravindakv.hiretrack.feed.web;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.feed.client.ServiceClients;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.service.FeedRuns;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class JobsController {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);
    private static final Set<String> STATUSES = Set.of("NEW", "SAVED", "DISMISSED");

    public record FeedItem(long postingId, String title, String company, String location, String url,
                           Integer salaryMin, Integer salaryMax, Instant postedAt, Integer quickScore,
                           Object missingSkills, String reason, String status, Long applicationId) {}

    public record FeedResponse(List<FeedItem> items, Instant lastRunAt, String lastError, String latestRunStatus) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FeedRuns runs;
    private final ServiceClients clients;
    private final IdempotencyService idempotency;
    private final FeedProperties.Feed props;
    private final ObjectMapper json;
    private final Clock clock;

    public JobsController(JdbcTemplate jdbc, TransactionTemplate tx, FeedRuns runs, ServiceClients clients,
                          IdempotencyService idempotency, FeedProperties props, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.runs = runs;
        this.clients = clients;
        this.idempotency = idempotency;
        this.props = props.feed();
        this.json = json;
        this.clock = clock;
    }

    @GetMapping("/jobs")
    FeedResponse list(@CurrentUser long userId, @RequestParam(defaultValue = "NEW") String status) {
        if (!STATUSES.contains(status)) throw ApiException.badRequest("INVALID_STATUS", "status must be NEW, SAVED or DISMISSED.");
        List<FeedItem> items = jdbc.query("""
                SELECT p.id, p.title, p.company, p.location, p.url, p.salary_min, p.salary_max, p.posted_at,
                       i.fit_score, i.missing_skills::text AS skills, i.reason, i.status, i.application_id
                FROM feed_items i JOIN postings p ON p.id = i.posting_id
                WHERE i.user_id = ? AND i.status = ?
                ORDER BY i.fit_score DESC NULLS LAST, p.posted_at DESC NULLS LAST
                LIMIT 200""",
                (rs, n) -> new FeedItem(rs.getLong("id"), rs.getString("title"), rs.getString("company"),
                        rs.getString("location"), rs.getString("url"), (Integer) rs.getObject("salary_min"),
                        (Integer) rs.getObject("salary_max"),
                        rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toInstant(),
                        (Integer) rs.getObject("fit_score"), readJson(rs.getString("skills")), rs.getString("reason"),
                        rs.getString("status"), (Long) rs.getObject("application_id")),
                userId, status);
        Map<String, Object> profile = jdbc.queryForList(
                "SELECT last_run_at, last_error FROM search_profiles WHERE user_id = ?", userId).stream().findFirst().orElse(Map.of());
        String latest = jdbc.queryForList(
                "SELECT status FROM feed_runs WHERE user_id = ? ORDER BY created_at DESC LIMIT 1", String.class, userId)
                .stream().findFirst().orElse(null);
        Object lastRun = profile.get("last_run_at");
        return new FeedResponse(items, lastRun == null ? null : ((java.sql.Timestamp) lastRun).toInstant(),
                (String) profile.get("last_error"), latest);
    }

    /** Spec 3.6: Refresh now, at most once an hour per user. */
    @PostMapping("/jobs/refresh")
    ResponseEntity<Map> refresh(@CurrentUser long userId, @RequestHeader(IdempotencyService.HEADER) String key) {
        var req = IdempotentRequest.of("feed-refresh", userId, key, "POST", "/jobs/refresh", null, json);
        return idempotency.execute(req, Map.class, () -> tx.execute(s -> {
            Map<String, Object> p = jdbc.queryForList(
                    "SELECT last_manual_run_at FROM search_profiles WHERE user_id = ? FOR UPDATE", userId)
                    .stream().findFirst().orElseThrow(() -> ApiException.conflict("NO_SEARCH_PROFILE", "Set up your job search first."));
            Instant last = p.get("last_manual_run_at") == null ? null : ((java.sql.Timestamp) p.get("last_manual_run_at")).toInstant();
            Instant now = clock.instant();
            if (last != null && last.plus(props.manualRefreshCooldown()).isAfter(now)) {
                long wait = java.time.Duration.between(now, last.plus(props.manualRefreshCooldown())).toSeconds();
                throw ApiException.tooManyRequests("REFRESH_TOO_SOON", "You can refresh once an hour.", (int) Math.max(1, wait));
            }
            UUID runId = runs.request(userId, "manual:" + userId + ":" + HOUR.format(now), "MANUAL");
            jdbc.update("UPDATE search_profiles SET last_manual_run_at = ? WHERE user_id = ?", java.sql.Timestamp.from(now), userId);
            Map<String, Object> body = new HashMap<>();
            body.put("runId", runId == null ? null : runId.toString());
            return ResponseEntity.accepted().body(body);
        }));
    }

    /** Creates a Wishlist application through application-service. Safe to retry at any point. */
    @PostMapping("/jobs/{postingId}/save")
    Map<String, Object> save(@CurrentUser long userId, @PathVariable long postingId) {
        Map<String, Object> item = jdbc.queryForList("""
                SELECT p.title, coalesce(p.company, 'Unknown company') AS company, p.url, i.status, i.application_id
                FROM feed_items i JOIN postings p ON p.id = i.posting_id
                WHERE i.user_id = ? AND i.posting_id = ? AND i.status IN ('NEW', 'SAVED', 'DISMISSED')""", userId, postingId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Posting"));
        if (item.get("application_id") != null) {
            return Map.of("postingId", postingId, "applicationId", item.get("application_id"));
        }
        long applicationId = clients.createApplication(userId, "feed-save-" + userId + "-" + postingId, Map.of(
                "company", truncate((String) item.get("company"), 200),
                "role", truncate((String) item.get("title"), 200),
                "jobUrl", item.get("url"),
                "source", Map.of("type", "FEED", "id", Long.toString(postingId))));
        jdbc.update("UPDATE feed_items SET status = 'SAVED', application_id = ? WHERE user_id = ? AND posting_id = ?",
                applicationId, userId, postingId);
        return Map.of("postingId", postingId, "applicationId", applicationId);
    }

    @PostMapping("/jobs/{postingId}/dismiss")
    ResponseEntity<Void> dismiss(@CurrentUser long userId, @PathVariable long postingId) {
        int n = jdbc.update("""
                UPDATE feed_items SET status = 'DISMISSED'
                WHERE user_id = ? AND posting_id = ? AND status IN ('NEW', 'DISMISSED')""", userId, postingId);
        if (n == 0) throw ApiException.notFound("Posting");
        return ResponseEntity.noContent().build();
    }

    private Object readJson(String s) {
        try { return s == null ? List.of() : json.readValue(s, List.class); } catch (Exception e) { return List.of(); }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

## Step 8.11 — Run it on its own with fakes

WireMock stubs. Fake Adzuna, `deploy/wiremock/mappings/adzuna-search.json`:

```json
{
  "request": { "method": "GET", "urlPathPattern": "/v1/api/jobs/[a-z]{2}/search/1" },
  "response": {
    "status": 200, "headers": { "Content-Type": "application/json" },
    "jsonBody": { "results": [
      { "id": "ad-1", "title": "Java Backend Engineer", "company": { "display_name": "Globex" },
        "location": { "display_name": "Bangalore" }, "redirect_url": "https://example.com/ad-1",
        "description": "Java, Spring Boot, Kafka microservices", "salary_min": 2000000, "created": "2026-11-10T08:00:00Z" },
      { "id": "ad-2", "title": "Pastry Chef", "company": { "display_name": "Bakery" },
        "location": { "display_name": "Bangalore" }, "redirect_url": "https://example.com/ad-2",
        "description": "Croissants and cakes", "created": "2026-11-11T08:00:00Z" }
    ] }
  }
}
```

Fake ai-service batch endpoints, `deploy/wiremock/mappings/ai-feed-batch.json`. The poll stub uses response templating to echo back the runs it was given, so it works for any run ID:

```json
{
  "mappings": [
    { "request": { "method": "POST", "url": "/internal/feed-batches" },
      "response": { "status": 200, "headers": { "Content-Type": "application/json" },
                    "body": "{\"batchId\":\"{{jsonPath request.body '$.items[0].customId'}}\"}",
                    "transformers": ["response-template"] } },
    { "request": { "method": "GET", "urlPathPattern": "/internal/feed-batches/.+" },
      "response": { "status": 200, "headers": { "Content-Type": "application/json" },
                    "body": "{\"status\":\"ENDED\",\"items\":[{\"customId\":\"{{request.pathSegments.[2]}}\",\"status\":\"SUCCEEDED\",\"scores\":[]}]}",
                    "transformers": ["response-template"] } }
  ]
}
```

That fake returns no scores, so items end as `FILTERED`. It's enough to check the flow. Use the real ai-service (Phase 5) to see real scores.

The resume stub from Phase 6 (`resume-active.json`) is reused. Then:

```bash
curl -s -X POST localhost:8089/__admin/mappings/reset
ADZUNA_BASE_URL=http://localhost:8089 RESUME_SERVICE_URL=http://localhost:8089 AI_SERVICE_URL=http://localhost:8089 \
APPLICATION_SERVICE_URL=http://localhost:8085 tools/run-service.sh job-feed-service
```

**Verify**

```bash
TOKEN=$(tools/mint-jwt.sh 1); H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
curl -s -X PUT localhost:8086/search-profile "${H[@]}" -d '{"keywords":"java developer","country":"in","location":"Bangalore","radiusKm":25,"remoteOk":false,"enabled":true,"timezone":"Asia/Kolkata"}' | jq -c '{nextRunAt}'
curl -s -X POST localhost:8086/jobs/refresh "${H[@]}" -H "Idempotency-Key: $(uuidgen)" | jq -c .
sleep 5
docker compose -f deploy/docker-compose.yml exec -T postgres psql "postgresql://feed_svc:feed_pw@localhost/hiretrack" \
  -c "select trigger, status from feed_runs" -c "select posting_id, status, prefilter_score from feed_items"
curl -s -X POST localhost:8086/jobs/refresh "${H[@]}" -H "Idempotency-Key: $(uuidgen)" | jq -c .error
```

Expected:

1. A `nextRunAt` at about 07:xx Kolkata time.
2. `{"runId":"..."}`.
3. A run that has moved to `READY_TO_SCORE`. Within about 2 minutes it reaches `SCORING`, then `DONE` once the submitter and poller run. Both postings are stored; the Java one has the higher `prefilter_score`.
4. `{"code":"REFRESH_TOO_SOON",...}`

## Step 8.12 — Integration tests

`src/test/java/com/aravindakv/hiretrack/feed/JobFeedIT.java`:

```java
package com.aravindakv.hiretrack.feed;

import com.aravindakv.hiretrack.feed.service.BatchPoller;
import com.aravindakv.hiretrack.feed.service.BatchSubmitter;
import com.aravindakv.hiretrack.feed.service.FeedPlanner;
import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import tools.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JobFeedIT extends IntegrationTest {

    @RegisterExtension
    static WireMockExtension deps = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        for (String k : new String[]{"hiretrack.adzuna.base-url", "hiretrack.clients.resume-url",
                "hiretrack.clients.ai-url", "hiretrack.clients.application-url"}) {
            r.add(k, deps::baseUrl);
        }
    }

    @Autowired FeedPlanner planner;
    @Autowired BatchSubmitter submitter;
    @Autowired BatchPoller poller;
    @Autowired StringRedisTemplate redis;

    static final String ADZUNA = """
            {"results":[
              {"id":"j1","title":"Java Engineer","company":{"display_name":"Globex"},"location":{"display_name":"Pune"},
               "redirect_url":"https://example.com/j1","description":"Java Spring Kafka","created":"2026-11-10T08:00:00Z"},
              {"id":"j2","title":"Chef","company":{"display_name":"Bistro"},"location":{"display_name":"Pune"},
               "redirect_url":"https://example.com/j2","description":"Cooking","created":"2026-11-11T08:00:00Z"}]}""";

    @BeforeEach
    void stubs() {
        deps.resetAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        deps.stubFor(get(urlPathMatching("/v1/api/jobs/in/search/1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(ADZUNA)));
        deps.stubFor(get(urlPathMatching("/internal/users/[0-9]+/active-resume")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"resumeId\":3,\"content\":\"Java Spring Kafka developer\"}")));
        deps.stubFor(post("/internal/feed-batches").willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"batchId\":\"b-1\"}")));
    }

    private void profile(long user, String keywords) {
        http.exchange("/search-profile", HttpMethod.PUT, new HttpEntity<>(Map.of("keywords", keywords, "country", "in",
                "location", "Pune", "radiusKm", 25, "remoteOk", false, "enabled", true, "timezone", "UTC"), auth(user)), JsonNode.class);
        jdbc.update("UPDATE search_profiles SET next_run_at = now() - interval '1 minute' WHERE user_id = ?", user);
        jdbc.update("INSERT INTO user_activity (user_id, last_seen_on) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET last_seen_on = EXCLUDED.last_seen_on",
                user, java.sql.Date.valueOf(LocalDate.now()));
    }

    private UUID awaitRun(long user, String status) {
        await().atMost(Duration.ofSeconds(15)).until(() -> status.equals(jdbc.queryForList(
                "SELECT status FROM feed_runs WHERE user_id = ? ORDER BY created_at DESC LIMIT 1", String.class, user)
                .stream().findFirst().orElse(null)));
        return jdbc.queryForObject("SELECT id FROM feed_runs WHERE user_id = ? ORDER BY created_at DESC LIMIT 1", UUID.class, user);
    }

    private void stubBatchResult(UUID runId, long javaPostingId) {
        deps.stubFor(get("/internal/feed-batches/b-1").willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":"ENDED","items":[{"customId":"%s","status":"SUCCEEDED",
                          "scores":[{"postingId":"%d","fitScore":88,"missingSkills":["Docker"],"reason":"Strong Java match"}]}]}"""
                        .formatted(runId, javaPostingId))));
    }

    @Test
    void fullRunFromPlannerToRankedFeed() {
        profile(1101, "java");
        assertThat(planner.plan()).isEqualTo(1);
        UUID runId = awaitRun(1101, "READY_TO_SCORE");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_items WHERE run_id = ?", Integer.class, runId)).isEqualTo(2);
        long javaId = jdbc.queryForObject("SELECT id FROM postings WHERE external_id = 'j1'", Long.class);

        submitter.submit();
        assertThat(awaitRun(1101, "SCORING")).isEqualTo(runId);
        stubBatchResult(runId, javaId);
        poller.poll();
        awaitRun(1101, "DONE");

        JsonNode feed = http.exchange("/jobs", HttpMethod.GET, new HttpEntity<>(auth(1101)), JsonNode.class).getBody();
        assertThat(feed.get("items")).hasSize(1);
        assertThat(feed.get("items").get(0).get("quickScore").asInt()).isEqualTo(88);
        assertThat(feed.get("latestRunStatus").asString()).isEqualTo("DONE");
    }

    @Test
    void planningTwiceCreatesOneRunPerDay() {
        profile(1102, "java");
        planner.plan();
        jdbc.update("UPDATE search_profiles SET next_run_at = now() - interval '1 minute' WHERE user_id = 1102");
        planner.plan();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_runs WHERE user_id = 1102 AND trigger = 'SCHEDULED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void inactiveUsersAreNotPlanned() {
        profile(1103, "java");
        jdbc.update("UPDATE user_activity SET last_seen_on = ? WHERE user_id = 1103", java.sql.Date.valueOf(LocalDate.now().minusDays(30)));
        assertThat(planner.plan()).isZero();
    }

    @Test
    void usersWithTheSameSearchShareOneAdzunaCall() {
        profile(1104, "java developer");
        profile(1105, "Developer  JAVA");                 // normalises to the same query key
        planner.plan();
        awaitRun(1104, "READY_TO_SCORE");
        awaitRun(1105, "READY_TO_SCORE");
        deps.verify(1, getRequestedFor(urlPathMatching("/v1/api/jobs/in/search/1")));
    }

    @Test
    void dismissedPostingsNeverComeBack() {
        profile(1106, "java");
        planner.plan();
        UUID runId = awaitRun(1106, "READY_TO_SCORE");
        long javaId = jdbc.queryForObject("SELECT id FROM postings WHERE external_id = 'j1'", Long.class);
        submitter.submit();
        awaitRun(1106, "SCORING");
        stubBatchResult(runId, javaId);
        poller.poll();
        awaitRun(1106, "DONE");

        http.exchange("/jobs/" + javaId + "/dismiss", HttpMethod.POST, new HttpEntity<>(auth(1106)), Void.class);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();       // force a fresh fetch
        var refresh = http.exchange("/jobs/refresh", HttpMethod.POST, new HttpEntity<>(authWithKey(1106, newKey())), JsonNode.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitRun(1106, "NOTHING_NEW");                    // both postings already seen
        assertThat(jdbc.queryForObject("SELECT status FROM feed_items WHERE user_id = 1106 AND posting_id = ?", String.class, javaId))
                .isEqualTo("DISMISSED");
    }

    @Test
    void secondRefreshWithinTheHourIs429() {
        profile(1107, "java");
        assertThat(http.exchange("/jobs/refresh", HttpMethod.POST, new HttpEntity<>(authWithKey(1107, newKey())), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var second = http.exchange("/jobs/refresh", HttpMethod.POST, new HttpEntity<>(authWithKey(1107, newKey())), JsonNode.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getBody().at("/error/code").asString()).isEqualTo("REFRESH_TOO_SOON");
    }

    @Test
    void savingIsIdempotentAcrossRetries() {
        profile(1108, "java");
        planner.plan();
        UUID runId = awaitRun(1108, "READY_TO_SCORE");
        long javaId = jdbc.queryForObject("SELECT id FROM postings WHERE external_id = 'j1'", Long.class);
        submitter.submit();
        awaitRun(1108, "SCORING");
        stubBatchResult(runId, javaId);
        poller.poll();
        awaitRun(1108, "DONE");

        deps.stubFor(post("/internal/users/1108/applications").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":555}")));
        var first = http.exchange("/jobs/" + javaId + "/save", HttpMethod.POST, new HttpEntity<>(auth(1108)), JsonNode.class);
        var again = http.exchange("/jobs/" + javaId + "/save", HttpMethod.POST, new HttpEntity<>(auth(1108)), JsonNode.class);
        assertThat(first.getBody().get("applicationId").asLong()).isEqualTo(555);
        assertThat(again.getBody().get("applicationId").asLong()).isEqualTo(555);
        deps.verify(1, postRequestedFor(urlEqualTo("/internal/users/1108/applications"))
                .withHeader("Idempotency-Key", equalTo("feed-save-1108-" + javaId)));
    }

    @Test
    void adzunaOutageIsRecordedOnTheProfile() {
        deps.stubFor(get(urlPathMatching("/v1/api/jobs/in/search/1")).willReturn(aResponse().withStatus(503)));
        profile(1109, "java");
        planner.plan();
        awaitRun(1109, "FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM search_profiles WHERE user_id = 1109", String.class))
                .contains("unavailable");
    }
}
```

**Verify**

```bash
./gradlew :services:job-feed-service:test
```

Expected: 12 tests pass (4 unit, 8 integration).

---

## Phase demo

Run the whole back end: identity, resume, ai (real OpenAI key in `.env`), application and job-feed. Set real `ADZUNA_APP_ID` and `ADZUNA_APP_KEY` in `.env`. Then `tools/demo-phase8.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
TOKEN=$(tools/mint-jwt.sh 1200); H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
psql_feed() { docker compose -f deploy/docker-compose.yml exec -T postgres psql "postgresql://feed_svc:feed_pw@localhost/hiretrack" -tAc "$1"; }

curl -s -X POST localhost:8082/resumes "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"text":"Backend engineer: Java 25, Spring Boot, Kafka, PostgreSQL, Docker, AWS. 6 years."}' >/dev/null
curl -s -X PUT localhost:8086/search-profile "${H[@]}" \
  -d '{"keywords":"java developer","country":"in","location":"Bangalore","radiusKm":25,"remoteOk":true,"enabled":true,"timezone":"Asia/Kolkata"}' >/dev/null
curl -s -X POST localhost:8086/jobs/refresh "${H[@]}" -H "Idempotency-Key: $(uuidgen)" >/dev/null

echo "waiting for the run (submitter every 2 min, provider batch can take minutes)..."
until [ "$(psql_feed "select status from feed_runs where user_id=1200 order by created_at desc limit 1")" = "DONE" ]; do sleep 15; done
curl -s localhost:8086/jobs "${H[@]}" | jq -r '.items[:5][] | "\(.quickScore)\t\(.title) — \(.company)"'
```

Expected: after a few minutes (the provider's batch processing time varies), the top five real postings near Bangalore, ranked by quick score.

## Done when

- [ ] 12 tests pass
- [ ] Planner runs are idempotent per user per day; inactive users are skipped
- [ ] Identical searches share one Adzuna call; dismissed postings never return
- [ ] Batch scoring flows submit → score → `DONE`, and failures retry up to 3 times
- [ ] Save to pipeline is idempotent; refresh is limited to once an hour
- [ ] A real end-to-end feed works with Adzuna and OpenAI
- [ ] Commit: `git commit -am "Phase 8: job-feed-service"`
