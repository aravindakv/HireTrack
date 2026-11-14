# Phase 4 — Application Service

**Goal:** the application pipeline (spec 3.4), with:

- the stage rules enforced in code, and every row of the spec table covered by a unit test
- safe concurrent edits through `ETag` / `If-Match`
- idempotent creation, including "save this match" and "save this feed posting"
- an event for every change, so analytics-service can build the dashboard

**Time:** 2 days.

**Runs on its own with:** Docker Compose infrastructure, a minted token, and WireMock standing in for match-service.

---

## Step 4.1 — Module and build file

Add to `settings.gradle.kts`:

```kotlin
include("services:application-service")
```

`services/application-service/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:idempotency"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
}
```

`src/main/java/com/aravindakv/hiretrack/application/ApplicationServiceApp.java` (named so it doesn't clash with the domain class):

```java
package com.aravindakv.hiretrack.application;

import com.aravindakv.hiretrack.common.web.EnableHiretrackSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@EnableHiretrackSecurity
@ConfigurationPropertiesScan
public class ApplicationServiceApp {
    public static void main(String[] args) {
        SpringApplication.run(ApplicationServiceApp.class, args);
    }
}
```

## Step 4.2 — Configuration

`application.yml`: the Phase 1 template with `<service>` = `application-service`, `<schema>` = `application`, `<port>` = `8085`, and flyway locations `classpath:db/migration,classpath:db/platform/outbox,classpath:db/platform/idempotency`. Add:

```yaml
hiretrack:
  clients:
    match-url: ${MATCH_SERVICE_URL:http://localhost:8084}
```

To run this service on its own, point `MATCH_SERVICE_URL` at WireMock (`http://localhost:8089`); Step 4.8 shows how.

## Step 4.3 — Migrations

`db/migration/V1__applications.sql`:

```sql
CREATE TABLE applications (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    company       TEXT        NOT NULL CHECK (length(company) BETWEEN 1 AND 200),
    role          TEXT        NOT NULL CHECK (length(role) BETWEEN 1 AND 200),
    stage         TEXT        NOT NULL DEFAULT 'WISHLIST'
                  CHECK (stage IN ('WISHLIST', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED')),
    job_url       TEXT,
    applied_date  DATE,
    responded_at  TIMESTAMPTZ,
    notes         TEXT,
    source_type   TEXT        NOT NULL DEFAULT 'MANUAL' CHECK (source_type IN ('MANUAL', 'MATCH', 'FEED')),
    source_id     TEXT,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((source_type = 'MANUAL') = (source_id IS NULL))
);
CREATE INDEX applications_user_stage ON applications (user_id, stage);
-- A match or a feed posting becomes at most one application (replaces the class build's unique FK)
CREATE UNIQUE INDEX applications_one_per_source ON applications (user_id, source_type, source_id)
    WHERE source_id IS NOT NULL;
```

`db/migration/V2__interviews.sql`:

```sql
CREATE TABLE interviews (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT      NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    scheduled_at   TIMESTAMPTZ NOT NULL,
    round          TEXT CHECK (length(round) <= 100),
    notes          TEXT,
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX interviews_application ON interviews (application_id);
CREATE INDEX interviews_scheduled_at ON interviews (scheduled_at);
```

## Step 4.4 — Domain: stage rules first

`domain/Stage.java`:

```java
package com.aravindakv.hiretrack.application.domain;

public enum Stage {
    WISHLIST, APPLIED, INTERVIEW, OFFER, REJECTED;

    /** Moving here means the application was sent. */
    public boolean countsAsSent() {
        return this == APPLIED || this == INTERVIEW || this == OFFER;
    }

    /** Moving here (after sending) means the company replied. A rejection is a reply. */
    public boolean countsAsReply() {
        return this == INTERVIEW || this == OFFER || this == REJECTED;
    }
}
```

`domain/SourceType.java`:

```java
package com.aravindakv.hiretrack.application.domain;

public enum SourceType { MANUAL, MATCH, FEED }
```

`domain/JobApplication.java`:

```java
package com.aravindakv.hiretrack.application.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "applications")
public class JobApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String company;

    @Column(nullable = false)
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Stage stage = Stage.WISHLIST;

    @Column(name = "job_url")
    private String jobUrl;

    @Column(name = "applied_date")
    private LocalDate appliedDate;

    @Column(name = "responded_at")
    private Instant respondedAt;

    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private SourceType sourceType = SourceType.MANUAL;

    @Column(name = "source_id", updatable = false)
    private String sourceId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected JobApplication() {}

    public JobApplication(Long userId, String company, String role, String jobUrl, String notes,
                          SourceType sourceType, String sourceId) {
        this.userId = userId;
        this.company = company;
        this.role = role;
        this.jobUrl = jobUrl;
        this.notes = notes;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
    }

    /**
     * Spec 3.4 stage rules. Returns false when nothing changed (moving to the current stage),
     * so repeating the same request is harmless.
     */
    public boolean moveTo(Stage target, LocalDate today, Instant now) {
        if (target == stage) return false;
        if (target == Stage.WISHLIST) {
            appliedDate = null;
            respondedAt = null;
        } else {
            if (target.countsAsSent() && appliedDate == null) {
                appliedDate = today;
            }
            if (target.countsAsReply() && appliedDate != null && respondedAt == null) {
                respondedAt = now;
            }
        }
        stage = target;
        return true;
    }

    /** The user may correct the applied date, but only on an application that was sent. */
    public void correctAppliedDate(LocalDate date) {
        if (appliedDate == null) {
            throw new IllegalStateException("Only a sent application has an applied date");
        }
        appliedDate = date;
    }

    public void edit(String company, String role, String jobUrl, String notes) {
        if (company != null) this.company = company;
        if (role != null) this.role = role;
        if (jobUrl != null) this.jobUrl = jobUrl.isBlank() ? null : jobUrl;
        if (notes != null) this.notes = notes.isBlank() ? null : notes;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getCompany() { return company; }
    public String getRole() { return role; }
    public Stage getStage() { return stage; }
    public String getJobUrl() { return jobUrl; }
    public LocalDate getAppliedDate() { return appliedDate; }
    public Instant getRespondedAt() { return respondedAt; }
    public String getNotes() { return notes; }
    public SourceType getSourceType() { return sourceType; }
    public String getSourceId() { return sourceId; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

`domain/Interview.java`:

```java
package com.aravindakv.hiretrack.application.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "interviews")
public class Interview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    private String round;
    private String notes;

    @Version
    private Long version;

    protected Interview() {}

    public Interview(Long applicationId, Instant scheduledAt, String round, String notes) {
        this.applicationId = applicationId;
        this.scheduledAt = scheduledAt;
        this.round = round;
        this.notes = notes;
    }

    public void edit(Instant scheduledAt, String round, String notes) {
        if (scheduledAt != null) this.scheduledAt = scheduledAt;
        if (round != null) this.round = round.isBlank() ? null : round;
        if (notes != null) this.notes = notes.isBlank() ? null : notes;
    }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public Instant getScheduledAt() { return scheduledAt; }
    public String getRound() { return round; }
    public String getNotes() { return notes; }
    public Long getVersion() { return version; }
}
```

### Unit-test the stage rules before anything else

`src/test/java/com/aravindakv/hiretrack/application/domain/StageRulesTest.java`. One test per row of the spec 3.4 table:

```java
package com.aravindakv.hiretrack.application.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class StageRulesTest {

    static final LocalDate DAY1 = LocalDate.of(2026, 11, 2);
    static final LocalDate DAY5 = LocalDate.of(2026, 11, 6);
    static final Instant T1 = Instant.parse("2026-11-02T10:00:00Z");
    static final Instant T5 = Instant.parse("2026-11-06T10:00:00Z");

    private JobApplication fresh() {
        return new JobApplication(1L, "Acme", "Engineer", null, null, SourceType.MANUAL, null);
    }

    @Test
    void newApplicationStartsInWishlistWithNoDates() {
        JobApplication a = fresh();
        assertThat(a.getStage()).isEqualTo(Stage.WISHLIST);
        assertThat(a.getAppliedDate()).isNull();
        assertThat(a.getRespondedAt()).isNull();
    }

    @Test
    void movingToAppliedSetsAppliedDate() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
        assertThat(a.getRespondedAt()).isNull();
    }

    @Test
    void skippingFromWishlistToInterviewSetsBothDates() {
        JobApplication a = fresh();
        a.moveTo(Stage.INTERVIEW, DAY1, T1);
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
        assertThat(a.getRespondedAt()).isEqualTo(T1);
    }

    @Test
    void replyAfterApplyingSetsRespondedAtOnce() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        a.moveTo(Stage.INTERVIEW, DAY5, T5);
        a.moveTo(Stage.OFFER, DAY5.plusDays(3), T5.plusSeconds(86400 * 3));
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
        assertThat(a.getRespondedAt()).isEqualTo(T5);          // the first reply, not the offer
    }

    @Test
    void rejectionAfterApplyingCountsAsReply() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        a.moveTo(Stage.REJECTED, DAY5, T5);
        assertThat(a.getRespondedAt()).isEqualTo(T5);
    }

    @Test
    void rejectedFromWishlistIsNeitherSentNorReplied() {
        JobApplication a = fresh();
        a.moveTo(Stage.REJECTED, DAY1, T1);
        assertThat(a.getAppliedDate()).isNull();
        assertThat(a.getRespondedAt()).isNull();
    }

    @Test
    void movingBackToWishlistClearsBothDates() {
        JobApplication a = fresh();
        a.moveTo(Stage.INTERVIEW, DAY1, T1);
        a.moveTo(Stage.WISHLIST, DAY5, T5);
        assertThat(a.getAppliedDate()).isNull();
        assertThat(a.getRespondedAt()).isNull();
    }

    @Test
    void movingOutOfRejectedKeepsDates() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        a.moveTo(Stage.REJECTED, DAY5, T5);
        a.moveTo(Stage.INTERVIEW, DAY5.plusDays(1), T5.plusSeconds(86400));
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
        assertThat(a.getRespondedAt()).isEqualTo(T5);
    }

    @Test
    void movingToTheCurrentStageIsANoOp() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        assertThat(a.moveTo(Stage.APPLIED, DAY5, T5)).isFalse();
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
    }
}
```

**Verify**

```bash
./gradlew :services:application-service:test --tests '*StageRulesTest'
```

Expected: 9 tests pass in well under a second. They need no Spring and no database.

## Step 4.5 — Repositories and the match-service client

`persistence/ApplicationRepository.java`:

```java
package com.aravindakv.hiretrack.application.persistence;

import com.aravindakv.hiretrack.application.domain.JobApplication;
import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.domain.Stage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<JobApplication, Long> {

    List<JobApplication> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<JobApplication> findByUserIdAndStageOrderByUpdatedAtDesc(Long userId, Stage stage);

    Optional<JobApplication> findByIdAndUserId(Long id, Long userId);

    Optional<JobApplication> findByUserIdAndSourceTypeAndSourceId(Long userId, SourceType sourceType, String sourceId);
}
```

`persistence/InterviewRepository.java`:

```java
package com.aravindakv.hiretrack.application.persistence;

import com.aravindakv.hiretrack.application.domain.Interview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InterviewRepository extends JpaRepository<Interview, Long> {

    List<Interview> findByApplicationIdOrderByScheduledAtAsc(Long applicationId);

    /** Interviews have no user_id: ownership is checked through the parent application (spec 7.3). */
    @Query("""
            SELECT i FROM Interview i
            WHERE i.id = :id
              AND EXISTS (SELECT 1 FROM JobApplication a WHERE a.id = i.applicationId AND a.userId = :userId)""")
    Optional<Interview> findOwned(Long id, Long userId);
}
```

`client/MatchClient.java`:

```java
package com.aravindakv.hiretrack.application.client;

import com.aravindakv.hiretrack.common.web.InternalRestClients;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class MatchClient {

    public record MatchRef(long id, String company, String role) {}

    private final RestClient rest;

    public MatchClient(RestClient.Builder builder, ObjectMapper json,
                       @Value("${hiretrack.clients.match-url}") String baseUrl,
                       @Value("${hiretrack.internal.token}") String token) {
        this.rest = InternalRestClients.create(builder, baseUrl, token, Duration.ofSeconds(3), json);
    }

    /** Throws ApiException 404 when the match doesn't exist or belongs to someone else. */
    public MatchRef get(long userId, long matchId) {
        return rest.get().uri("/internal/users/{userId}/matches/{matchId}", userId, matchId)
                .retrieve().body(MatchRef.class);
    }
}
```

## Step 4.6 — Application service

`service/ApplicationEvents.java`:

```java
package com.aravindakv.hiretrack.application.service;

import com.aravindakv.hiretrack.application.domain.Interview;
import com.aravindakv.hiretrack.application.domain.JobApplication;
import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

/** Every application event carries a full snapshot, so consumers never need to call back. */
@Component
public class ApplicationEvents {

    public record ApplicationSnapshot(long applicationId, String company, String role, String stage,
                                      String fromStage, LocalDate appliedDate, Instant respondedAt) {}

    public record InterviewSnapshot(long interviewId, long applicationId, Instant scheduledAt, String round) {}

    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public ApplicationEvents(OutboxWriter outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void application(String type, JobApplication a, String fromStage) {
        var snapshot = new ApplicationSnapshot(a.getId(), a.getCompany(), a.getRole(), a.getStage().name(),
                fromStage, a.getAppliedDate(), a.getRespondedAt());
        outbox.write(Topics.APPLICATION_EVENTS, EventEnvelope.create(type, a.getUserId(),
                "application-" + a.getId(), a.getVersion(), snapshot, json));
    }

    public void interview(String type, long userId, Interview i) {
        var snapshot = new InterviewSnapshot(i.getId(), i.getApplicationId(), i.getScheduledAt(), i.getRound());
        outbox.write(Topics.APPLICATION_EVENTS, EventEnvelope.create(type, userId,
                "interview-" + i.getId(), i.getVersion(), snapshot, json));
    }
}
```

`service/ApplicationService.java`:

```java
package com.aravindakv.hiretrack.application.service;

import com.aravindakv.hiretrack.application.client.MatchClient;
import com.aravindakv.hiretrack.application.domain.Interview;
import com.aravindakv.hiretrack.application.domain.JobApplication;
import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.domain.Stage;
import com.aravindakv.hiretrack.application.persistence.ApplicationRepository;
import com.aravindakv.hiretrack.application.persistence.InterviewRepository;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class ApplicationService {

    public record NewApplication(String company, String role, String jobUrl, String notes,
                                 SourceType sourceType, String sourceId) {}

    public record Changes(String company, String role, String jobUrl, String notes, Stage stage, LocalDate appliedDate) {}

    public record Created(JobApplication application, boolean isNew) {}

    private final ApplicationRepository applications;
    private final InterviewRepository interviews;
    private final ApplicationEvents events;
    private final MatchClient matches;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, InterviewRepository interviews,
                              ApplicationEvents events, MatchClient matches, TransactionTemplate tx, Clock clock) {
        this.applications = applications;
        this.interviews = interviews;
        this.events = events;
        this.matches = matches;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<JobApplication> list(long userId, Stage stage) {
        return stage == null
                ? applications.findByUserIdOrderByUpdatedAtDesc(userId)
                : applications.findByUserIdAndStageOrderByUpdatedAtDesc(userId, stage);
    }

    @Transactional(readOnly = true)
    public JobApplication get(long userId, long id) {
        return owned(userId, id);
    }

    /**
     * Creates an application. Saving the same match or posting twice returns the existing one
     * (isNew = false) instead of failing: idempotent by design.
     * @param verifySource true for browser requests (check the match belongs to this user);
     *                     false for trusted internal callers (job-feed-service)
     */
    public Created create(long userId, NewApplication req, boolean verifySource) {
        if (verifySource && req.sourceType() == SourceType.MATCH) {
            matches.get(userId, Long.parseLong(req.sourceId()));     // 404 if missing or not owned
        }
        if (verifySource && req.sourceType() == SourceType.FEED) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "Save feed postings from the Job Feed tab.");
        }
        if (req.sourceId() != null) {
            var existing = tx.execute(s -> applications.findByUserIdAndSourceTypeAndSourceId(
                    userId, req.sourceType(), req.sourceId()));
            if (existing.isPresent()) return new Created(existing.get(), false);
        }
        try {
            return tx.execute(s -> {
                JobApplication saved = applications.saveAndFlush(new JobApplication(userId, req.company(), req.role(),
                        req.jobUrl(), req.notes(), req.sourceType(), req.sourceId()));
                events.application(EventTypes.APPLICATION_CREATED, saved, null);
                return new Created(saved, true);
            });
        } catch (DataIntegrityViolationException e) {        // a concurrent save of the same source won
            return tx.execute(s -> new Created(applications.findByUserIdAndSourceTypeAndSourceId(
                    userId, req.sourceType(), req.sourceId()).orElseThrow(() -> e), false));
        }
    }

    @Transactional
    public JobApplication update(long userId, long id, long expectedVersion, Changes c) {
        JobApplication a = owned(userId, id);
        if (a.getVersion() != expectedVersion) {
            throw ApiException.preconditionFailed("This application was changed elsewhere. Reload it and try again.");
        }
        String fromStage = a.getStage().name();
        boolean stageChanged = false;
        if (c.stage() != null) {
            stageChanged = a.moveTo(c.stage(), LocalDate.now(clock), clock.instant());
        }
        if (c.appliedDate() != null) {
            if (a.getAppliedDate() == null) {
                throw ApiException.badRequest("NOT_SENT", "Only a sent application has an applied date.");
            }
            a.correctAppliedDate(c.appliedDate());
        }
        a.edit(c.company(), c.role(), c.jobUrl(), c.notes());
        JobApplication saved = applications.saveAndFlush(a);
        events.application(stageChanged ? EventTypes.APPLICATION_STAGE_CHANGED : EventTypes.APPLICATION_UPDATED,
                saved, stageChanged ? fromStage : null);
        return saved;
    }

    @Transactional
    public void delete(long userId, long id) {
        JobApplication a = owned(userId, id);
        events.application(EventTypes.APPLICATION_DELETED, a, null);
        applications.delete(a);                         // interviews go with it (ON DELETE CASCADE)
    }

    @Transactional(readOnly = true)
    public List<Interview> interviews(long userId, long applicationId) {
        owned(userId, applicationId);
        return interviews.findByApplicationIdOrderByScheduledAtAsc(applicationId);
    }

    @Transactional
    public Interview addInterview(long userId, long applicationId, Instant scheduledAt, String round, String notes) {
        owned(userId, applicationId);
        Interview saved = interviews.saveAndFlush(new Interview(applicationId, scheduledAt, round, notes));
        events.interview(EventTypes.INTERVIEW_SCHEDULED, userId, saved);
        return saved;
    }

    @Transactional
    public Interview editInterview(long userId, long interviewId, Instant scheduledAt, String round, String notes) {
        Interview i = interviews.findOwned(interviewId, userId).orElseThrow(() -> ApiException.notFound("Interview"));
        i.edit(scheduledAt, round, notes);
        Interview saved = interviews.saveAndFlush(i);
        events.interview(EventTypes.INTERVIEW_UPDATED, userId, saved);
        return saved;
    }

    @Transactional
    public void deleteInterview(long userId, long interviewId) {
        Interview i = interviews.findOwned(interviewId, userId).orElseThrow(() -> ApiException.notFound("Interview"));
        events.interview(EventTypes.INTERVIEW_DELETED, userId, i);
        interviews.delete(i);
    }

    private JobApplication owned(long userId, long id) {
        return applications.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Application"));
    }
}
```

`config/KafkaTopicsConfig.java`:

```java
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
    NewTopic applicationEvents(@Value("${hiretrack.kafka.partitions}") int partitions,
                               @Value("${hiretrack.kafka.replicas}") int replicas) {
        return TopicBuilder.name(Topics.APPLICATION_EVENTS).partitions(partitions).replicas(replicas).build();
    }
}
```

## Step 4.7 — Controllers

`web/ApplicationDtos.java`:

```java
package com.aravindakv.hiretrack.application.web;

import com.aravindakv.hiretrack.application.domain.Interview;
import com.aravindakv.hiretrack.application.domain.JobApplication;
import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.domain.Stage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class ApplicationDtos {

    private ApplicationDtos() {}

    public record SourceRef(@NotNull SourceType type, @NotBlank @Size(max = 64) String id) {}

    public record CreateApplication(
            @NotBlank @Size(max = 200) String company,
            @NotBlank @Size(max = 200) String role,
            @Size(max = 2000) @Pattern(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String jobUrl,
            @Size(max = 5000) String notes,
            @Valid SourceRef source) {}

    /** All fields optional. An empty string clears jobUrl or notes. */
    public record UpdateApplication(
            @Size(min = 1, max = 200) String company,
            @Size(min = 1, max = 200) String role,
            @Size(max = 2000) @Pattern(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String jobUrl,
            @Size(max = 5000) String notes,
            Stage stage,
            LocalDate appliedDate) {}

    public record ApplicationResponse(long id, String company, String role, String stage, String jobUrl,
                                      LocalDate appliedDate, Instant respondedAt, String notes,
                                      String sourceType, String sourceId, long version,
                                      Instant createdAt, Instant updatedAt) {
        public static ApplicationResponse of(JobApplication a) {
            return new ApplicationResponse(a.getId(), a.getCompany(), a.getRole(), a.getStage().name(), a.getJobUrl(),
                    a.getAppliedDate(), a.getRespondedAt(), a.getNotes(), a.getSourceType().name(), a.getSourceId(),
                    a.getVersion(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    public record CreateInterview(@NotNull Instant scheduledAt, @Size(max = 100) String round, @Size(max = 5000) String notes) {}

    public record UpdateInterview(Instant scheduledAt, @Size(max = 100) String round, @Size(max = 5000) String notes) {}

    public record InterviewResponse(long id, long applicationId, Instant scheduledAt, String round, String notes) {
        public static InterviewResponse of(Interview i) {
            return new InterviewResponse(i.getId(), i.getApplicationId(), i.getScheduledAt(), i.getRound(), i.getNotes());
        }
    }
}
```

`web/ETags.java`:

```java
package com.aravindakv.hiretrack.application.web;

import com.aravindakv.hiretrack.common.web.ApiException;

final class ETags {

    private ETags() {}

    static String of(long version) {
        return "\"" + version + "\"";
    }

    /** Accepts "3", "\"3\"" and W/"3". */
    static long parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.preconditionRequired("Send If-Match with the ETag you loaded.");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) v = v.substring(2);
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_IF_MATCH", "If-Match must be the ETag you loaded.");
        }
    }
}
```

`web/ApplicationController.java`:

```java
package com.aravindakv.hiretrack.application.web;

import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.domain.Stage;
import com.aravindakv.hiretrack.application.service.ApplicationService;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.ApplicationResponse;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.CreateApplication;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.CreateInterview;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.InterviewResponse;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.UpdateApplication;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.UpdateInterview;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
public class ApplicationController {

    private final ApplicationService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public ApplicationController(ApplicationService service, IdempotencyService idempotency, ObjectMapper json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping("/applications")
    List<ApplicationResponse> list(@CurrentUser long userId, @RequestParam(required = false) Stage stage) {
        return service.list(userId, stage).stream().map(ApplicationResponse::of).toList();
    }

    @GetMapping("/applications/{id}")
    ResponseEntity<ApplicationResponse> get(@CurrentUser long userId, @PathVariable long id) {
        var a = service.get(userId, id);
        return ResponseEntity.ok().eTag(ETags.of(a.getVersion())).body(ApplicationResponse.of(a));
    }

    @PostMapping("/applications")
    ResponseEntity<ApplicationResponse> create(@CurrentUser long userId,
                                               @RequestHeader(IdempotencyService.HEADER) String key,
                                               @Valid @RequestBody CreateApplication body) {
        var req = IdempotentRequest.of("create-application", userId, key, "POST", "/applications", body, json);
        return idempotency.execute(req, ApplicationResponse.class, () -> {
            SourceType type = body.source() == null ? SourceType.MANUAL : body.source().type();
            String sourceId = body.source() == null ? null : body.source().id();
            var created = service.create(userId, new ApplicationService.NewApplication(
                    body.company(), body.role(), body.jobUrl(), body.notes(), type, sourceId), true);
            var a = created.application();
            return ResponseEntity.status(created.isNew() ? 201 : 200)
                    .location(URI.create("/applications/" + a.getId()))
                    .eTag(ETags.of(a.getVersion()))
                    .body(ApplicationResponse.of(a));
        });
    }

    @PatchMapping("/applications/{id}")
    ResponseEntity<ApplicationResponse> update(@CurrentUser long userId, @PathVariable long id,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @Valid @RequestBody UpdateApplication body) {
        var a = service.update(userId, id, ETags.parse(ifMatch), new ApplicationService.Changes(
                body.company(), body.role(), body.jobUrl(), body.notes(), body.stage(), body.appliedDate()));
        return ResponseEntity.ok().eTag(ETags.of(a.getVersion())).body(ApplicationResponse.of(a));
    }

    @DeleteMapping("/applications/{id}")
    ResponseEntity<Void> delete(@CurrentUser long userId, @PathVariable long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/applications/{id}/interviews")
    List<InterviewResponse> interviews(@CurrentUser long userId, @PathVariable long id) {
        return service.interviews(userId, id).stream().map(InterviewResponse::of).toList();
    }

    @PostMapping("/applications/{id}/interviews")
    ResponseEntity<InterviewResponse> addInterview(@CurrentUser long userId, @PathVariable long id,
                                                   @RequestHeader(IdempotencyService.HEADER) String key,
                                                   @Valid @RequestBody CreateInterview body) {
        var req = IdempotentRequest.of("create-interview", userId, key, "POST",
                "/applications/" + id + "/interviews", body, json);
        return idempotency.execute(req, InterviewResponse.class, () -> ResponseEntity.status(201).body(
                InterviewResponse.of(service.addInterview(userId, id, body.scheduledAt(), body.round(), body.notes()))));
    }

    @PatchMapping("/interviews/{id}")
    InterviewResponse editInterview(@CurrentUser long userId, @PathVariable long id,
                                    @Valid @RequestBody UpdateInterview body) {
        return InterviewResponse.of(service.editInterview(userId, id, body.scheduledAt(), body.round(), body.notes()));
    }

    @DeleteMapping("/interviews/{id}")
    ResponseEntity<Void> deleteInterview(@CurrentUser long userId, @PathVariable long id) {
        service.deleteInterview(userId, id);
        return ResponseEntity.noContent().build();
    }
}
```

`web/InternalApplicationController.java`, used by job-feed-service's "Save to pipeline":

```java
package com.aravindakv.hiretrack.application.web;

import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.service.ApplicationService;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.ApplicationResponse;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.CreateApplication;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalApplicationController {

    private final ApplicationService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public InternalApplicationController(ApplicationService service, IdempotencyService idempotency, ObjectMapper json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/internal/users/{userId}/applications")
    ResponseEntity<ApplicationResponse> create(@PathVariable long userId,
                                               @RequestHeader(IdempotencyService.HEADER) String key,
                                               @Valid @RequestBody CreateApplication body) {
        if (body.source() == null || body.source().type() != SourceType.FEED) {
            throw ApiException.badRequest("SOURCE_REQUIRED", "Internal creation needs a FEED source.");
        }
        var req = IdempotentRequest.of("create-application-internal", userId, key, "POST",
                "/internal/applications", body, json);
        return idempotency.execute(req, ApplicationResponse.class, () -> {
            var created = service.create(userId, new ApplicationService.NewApplication(body.company(), body.role(),
                    body.jobUrl(), body.notes(), SourceType.FEED, body.source().id()), false);
            return ResponseEntity.status(created.isNew() ? 201 : 200).body(ApplicationResponse.of(created.application()));
        });
    }
}
```

## Step 4.8 — Run it on its own with a fake match-service

Create the WireMock stub `deploy/wiremock/mappings/match-internal.json`:

```json
{
  "request": { "method": "GET", "urlPathPattern": "/internal/users/[0-9]+/matches/42" },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "id": 42, "company": "Acme", "role": "Backend Engineer" }
  }
}
```

```bash
curl -s -X POST localhost:8089/__admin/mappings/reset    # reload stubs from disk
MATCH_SERVICE_URL=http://localhost:8089 tools/run-service.sh application-service
```

**Verify**

```bash
TOKEN=$(tools/mint-jwt.sh 1)
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

# 1. Save match 42 as a Wishlist application, twice → the same application
for i in 1 2; do
  curl -s -o /dev/null -w '%{http_code} ' -X POST localhost:8085/applications "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
    -d '{"company":"Acme","role":"Backend Engineer","source":{"type":"MATCH","id":"42"}}'
done; echo
curl -s localhost:8085/applications "${H[@]}" | jq -c '[.[] | {id, stage, sourceId}]'

# 2. Move to APPLIED with the ETag
ID=$(curl -s localhost:8085/applications "${H[@]}" | jq '.[0].id')
ETAG=$(curl -s -D - -o /dev/null localhost:8085/applications/$ID "${H[@]}" | grep -i etag | cut -d' ' -f2 | tr -d '\r')
curl -s -X PATCH localhost:8085/applications/$ID "${H[@]}" -H "If-Match: $ETAG" -d '{"stage":"APPLIED"}' | jq -c '{stage, appliedDate, version}'

# 3. The same old ETag again → 412
curl -s -X PATCH localhost:8085/applications/$ID "${H[@]}" -H "If-Match: $ETAG" -d '{"stage":"INTERVIEW"}' | jq -c .error.code
```

Expected:

1. `201 200`, then a list with one application: `[{"id":1,"stage":"WISHLIST","sourceId":"42"}]`
2. `{"stage":"APPLIED","appliedDate":"<today>","version":1}`
3. `"PRECONDITION_FAILED"`

## Step 4.9 — Integration tests

`src/test/java/com/aravindakv/hiretrack/application/ApplicationIT.java`:

```java
package com.aravindakv.hiretrack.application;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicationIT extends IntegrationTest {

    @RegisterExtension
    static WireMockExtension matchService = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("hiretrack.clients.match-url", matchService::baseUrl);
    }

    @Autowired ObjectMapper json;

    private ResponseEntity<JsonNode> create(long user, Map<String, Object> body) {
        return http.exchange("/applications", HttpMethod.POST,
                new HttpEntity<>(body, authWithKey(user, newKey())), JsonNode.class);
    }

    private long createManual(long user) {
        return create(user, Map.of("company", "Acme", "role", "Engineer")).getBody().get("id").asLong();
    }

    private ResponseEntity<JsonNode> patch(long user, long id, String etag, Map<String, Object> body) {
        HttpHeaders h = auth(user);
        if (etag != null) h.set("If-Match", etag);
        return http.exchange("/applications/" + id, HttpMethod.PATCH, new HttpEntity<>(body, h), JsonNode.class);
    }

    private String etag(long user, long id) {
        return http.exchange("/applications/" + id, HttpMethod.GET, new HttpEntity<>(auth(user)), JsonNode.class)
                .getHeaders().getETag();
    }

    @Test
    void moveToAppliedSetsDateAndBumpsVersion() {
        long id = createManual(201);
        var res = patch(201, id, etag(201, id), Map.of("stage", "APPLIED"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("appliedDate").isNull()).isFalse();
        assertThat(res.getHeaders().getETag()).isEqualTo("\"1\"");
    }

    @Test
    void staleETagGets412AndMissingIfMatchGets428() {
        long id = createManual(202);
        String stale = etag(202, id);
        patch(202, id, stale, Map.of("stage", "APPLIED"));
        assertThat(patch(202, id, stale, Map.of("stage", "OFFER")).getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(patch(202, id, null, Map.of("stage", "OFFER")).getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void savingTheSameMatchTwiceReturnsTheSameApplication() {
        matchService.stubFor(get(urlPathMatching("/internal/users/203/matches/9")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":9,\"company\":\"Acme\",\"role\":\"SRE\"}")));
        Map<String, Object> body = Map.of("company", "Acme", "role", "SRE", "source", Map.of("type", "MATCH", "id", "9"));

        var first = create(203, body);
        var second = create(203, body);                      // different Idempotency-Key, same match
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    }

    @Test
    void savingSomeoneElsesMatchIs404() {
        matchService.stubFor(get(urlPathMatching("/internal/users/204/matches/10")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Match not found.\"}}")));
        var res = create(204, Map.of("company", "A", "role", "B", "source", Map.of("type", "MATCH", "id", "10")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUsersGet404ForEveryEndpoint() {
        long id = createManual(205);
        HttpEntity<?> asOther = new HttpEntity<>(auth(206));
        assertThat(http.exchange("/applications/" + id, HttpMethod.GET, asOther, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/applications/" + id + "/interviews", HttpMethod.GET, asOther, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/applications/" + id, HttpMethod.DELETE, asOther, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch(206, id, "\"0\"", Map.of("stage", "APPLIED")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void interviewsAreScopedThroughTheApplication() {
        long id = createManual(207);
        var created = http.exchange("/applications/" + id + "/interviews", HttpMethod.POST,
                new HttpEntity<>(Map.of("scheduledAt", "2026-12-01T09:30:00Z", "round", "HR"), authWithKey(207, newKey())),
                JsonNode.class);
        long interviewId = created.getBody().get("id").asLong();

        var asOther = http.exchange("/interviews/" + interviewId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("round", "Final"), auth(208)), JsonNode.class);
        assertThat(asOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var list = http.exchange("/applications/" + id + "/interviews", HttpMethod.GET, new HttpEntity<>(auth(207)), JsonNode.class);
        assertThat(list.getBody()).hasSize(1);
    }

    @Test
    void appliedDateCannotBeSetOnAWishlistApplication() {
        long id = createManual(209);
        var res = patch(209, id, etag(209, id), Map.of("appliedDate", "2026-11-01"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("NOT_SENT");
    }

    @Test
    void internalFeedSaveIsIdempotentPerPosting() {
        Map<String, Object> body = new HashMap<>(Map.of("company", "Globex", "role", "Data Engineer",
                "jobUrl", "https://example.com/job/1", "source", Map.of("type", "FEED", "id", "posting-77")));
        HttpHeaders h = internal();
        h.set("Idempotency-Key", "feed-save-210-posting-77");
        var first = http.exchange("/internal/users/210/applications", HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
        var retry = http.exchange("/internal/users/210/applications", HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM applications WHERE user_id = 210", Integer.class)).isEqualTo(1);
    }

    @Test
    void eachChangePublishesOneEventInOrder() {
        long id = createManual(211);
        patch(211, id, etag(211, id), Map.of("stage", "APPLIED"));
        patch(211, id, etag(211, id), Map.of("stage", "INTERVIEW"));

        var events = kafka().readAll("application-events", Duration.ofSeconds(4)).stream()
                .map(r -> EventEnvelope.parse(r.value(), json))
                .filter(e -> e.aggregateId().equals("application-" + id))
                .toList();
        assertThat(events).extracting(EventEnvelope::type)
                .containsExactly("application.created", "application.stage-changed", "application.stage-changed");
        assertThat(events).extracting(EventEnvelope::aggregateVersion).containsExactly(0L, 1L, 2L);
        assertThat(events.get(2).payload().get("respondedAt").isNull()).isFalse();
    }
}
```

**Verify**

```bash
./gradlew :services:application-service:test
```

Expected: 18 tests pass (9 stage-rule unit tests, 9 integration tests).

---

## Phase demo

`tools/demo-phase4.sh` (application-service alone, with the WireMock match stub from Step 4.8):

```bash
#!/usr/bin/env bash
set -euo pipefail
SVC=localhost:8085
TOKEN=$(tools/mint-jwt.sh 600)
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

ID=$(curl -s -X POST $SVC/applications "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"company":"Initech","role":"Platform Engineer"}' | jq .id)
for STAGE in APPLIED INTERVIEW OFFER WISHLIST; do
  ETAG=$(curl -s -D - -o /dev/null $SVC/applications/$ID "${H[@]}" | awk 'tolower($1)=="etag:"{print $2}' | tr -d '\r')
  curl -s -X PATCH $SVC/applications/$ID "${H[@]}" -H "If-Match: $ETAG" -d "{\"stage\":\"$STAGE\"}" \
    | jq -c '{stage, applied: (.appliedDate != null), replied: (.respondedAt != null)}'
done
```

Expected:

```text
{"stage":"APPLIED","applied":true,"replied":false}
{"stage":"INTERVIEW","applied":true,"replied":true}
{"stage":"OFFER","applied":true,"replied":true}
{"stage":"WISHLIST","applied":false,"replied":false}
```

## Done when

- [ ] 18 tests pass; every spec 3.4 stage rule is a unit test
- [ ] Stale `If-Match` → 412; missing → 428
- [ ] Saving the same match or posting twice gives one application
- [ ] User B gets 404 for every one of user A's IDs
- [ ] Events are published once per change, in order, with increasing `aggregateVersion`
- [ ] Commit: `git commit -am "Phase 4: application-service"`
