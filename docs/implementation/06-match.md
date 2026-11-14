# Phase 6 — Match Service

**Goal:** the AI Resume Match feature (spec 3.3). A user pastes a job description and gets a fit score, missing skills, three tailored bullets, and the company and role. The flow joins resume-service (the active resume) and ai-service (the AI call). It's idempotent end to end: a retried request never runs a second AI call or creates a second match.

**Time:** 1 day.

**Runs on its own with:** Docker Compose infrastructure, a minted token, and WireMock standing in for resume-service and ai-service.

---

## Step 6.1 — Module and build file

Add to `settings.gradle.kts`:

```kotlin
include("services:match-service")
```

`services/match-service/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:idempotency"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
}
```

`src/main/java/com/aravindakv/hiretrack/match/MatchApplication.java`:

```java
package com.aravindakv.hiretrack.match;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchApplication.class, args);
    }
}
```

## Step 6.2 — Configuration and the timeout chain

`application.yml`: the Phase 1 template with `<service>` = `match-service`, `<schema>` = `match`, `<port>` = `8084`, and flyway locations `classpath:db/migration,classpath:db/platform/outbox,classpath:db/platform/idempotency`. Add:

```yaml
hiretrack:
  clients:
    resume-url: ${RESUME_SERVICE_URL:http://localhost:8082}
    ai-url: ${AI_SERVICE_URL:http://localhost:8083}
    resume-timeout: 3s
    ai-timeout: 15s
  idempotency:
    lock-seconds: 30            # longer than the slowest match
```

Each hop waits a little longer than the hop behind it, so a caller never gives up while the callee is still working and might still succeed:

| Hop | Timeout | Set in |
| --- | --- | --- |
| Browser → gateway | 20 s | frontend API client (Phase 9) |
| Gateway → match-service | 17 s | gateway route `match-run` (Phase 2) |
| match-service → ai-service | 15 s | `hiretrack.clients.ai-timeout` |
| ai-service → AI provider | 2 attempts × 7 s | `hiretrack.ai.match-timeout` (Phase 5) |

## Step 6.3 — Migration and entity

`db/migration/V1__matches.sql`:

```sql
CREATE TABLE matches (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    resume_id         BIGINT      NOT NULL,           -- checked through resume-service, no cross-service FK
    job_description   TEXT        NOT NULL CHECK (length(job_description) BETWEEN 1 AND 20000),
    company           TEXT,
    role              TEXT,
    fit_score         INT         NOT NULL CHECK (fit_score BETWEEN 0 AND 100),
    missing_skills    JSONB       NOT NULL,
    suggested_bullets JSONB       NOT NULL,
    ai_provider       TEXT        NOT NULL,
    ai_model          TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX matches_user ON matches (user_id, created_at DESC);
```

`domain/Match.java`:

```java
package com.aravindakv.hiretrack.match.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "matches")
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false) private Long userId;
    @Column(name = "resume_id", nullable = false, updatable = false) private Long resumeId;
    @Column(name = "job_description", nullable = false, updatable = false) private String jobDescription;
    @Column(updatable = false) private String company;
    @Column(updatable = false) private String role;
    @Column(name = "fit_score", nullable = false, updatable = false) private int fitScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_skills", nullable = false, updatable = false)
    private List<String> missingSkills;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggested_bullets", nullable = false, updatable = false)
    private List<String> suggestedBullets;

    @Column(name = "ai_provider", nullable = false, updatable = false) private String aiProvider;
    @Column(name = "ai_model", nullable = false, updatable = false) private String aiModel;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected Match() {}

    public Match(Long userId, Long resumeId, String jobDescription, String company, String role, int fitScore,
                 List<String> missingSkills, List<String> suggestedBullets, String aiProvider, String aiModel) {
        this.userId = userId;
        this.resumeId = resumeId;
        this.jobDescription = jobDescription;
        this.company = company == null || company.isBlank() ? null : company;
        this.role = role == null || role.isBlank() ? null : role;
        this.fitScore = fitScore;
        this.missingSkills = missingSkills;
        this.suggestedBullets = suggestedBullets;
        this.aiProvider = aiProvider;
        this.aiModel = aiModel;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getResumeId() { return resumeId; }
    public String getJobDescription() { return jobDescription; }
    public String getCompany() { return company; }
    public String getRole() { return role; }
    public int getFitScore() { return fitScore; }
    public List<String> getMissingSkills() { return missingSkills; }
    public List<String> getSuggestedBullets() { return suggestedBullets; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`persistence/MatchRepository.java`:

```java
package com.aravindakv.hiretrack.match.persistence;

import com.aravindakv.hiretrack.match.domain.Match;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, Long> {
    List<Match> findTop100ByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<Match> findByIdAndUserId(Long id, Long userId);
}
```

## Step 6.4 — Clients for resume-service and ai-service

`client/Clients.java`:

```java
package com.aravindakv.hiretrack.match.client;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.InternalRestClients;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class Clients {

    public record ActiveResume(long resumeId, String content) {}

    public record AiMatch(int fitScore, List<String> missingSkills, List<String> suggestedBullets,
                          String company, String role, String provider, String model) {}

    private final RestClient resume;
    private final RestClient ai;

    public Clients(RestClient.Builder builder, ObjectMapper json,
                   @Value("${hiretrack.internal.token}") String token,
                   @Value("${hiretrack.clients.resume-url}") String resumeUrl,
                   @Value("${hiretrack.clients.ai-url}") String aiUrl,
                   @Value("${hiretrack.clients.resume-timeout}") Duration resumeTimeout,
                   @Value("${hiretrack.clients.ai-timeout}") Duration aiTimeout) {
        this.resume = InternalRestClients.create(builder, resumeUrl, token, resumeTimeout, json);
        this.ai = InternalRestClients.create(builder, aiUrl, token, aiTimeout, json);
    }

    /** No active resume becomes 409 for the browser (spec 3.3). */
    public ActiveResume activeResume(long userId) {
        try {
            return resume.get().uri("/internal/users/{id}/active-resume", userId).retrieve().body(ActiveResume.class);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.conflict("NO_ACTIVE_RESUME", "Add a resume before running a match.");
            }
            throw e;
        }
    }

    public AiMatch match(long userId, String requestId, String resumeText, String jobDescription) {
        return ai.post().uri("/internal/match")
                .body(Map.of("userId", userId, "requestId", requestId,
                        "resumeText", resumeText, "jobDescription", jobDescription))
                .retrieve().body(AiMatch.class);
    }
}
```

## Step 6.5 — Match service

`service/MatchService.java`:

```java
package com.aravindakv.hiretrack.match.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import com.aravindakv.hiretrack.match.client.Clients;
import com.aravindakv.hiretrack.match.domain.Match;
import com.aravindakv.hiretrack.match.persistence.MatchRepository;
import com.aravindakv.hiretrack.match.web.MatchDtos.MatchResponse;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
public class MatchService {

    private final Clients clients;
    private final MatchRepository matches;
    private final OutboxWriter outbox;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public MatchService(Clients clients, MatchRepository matches, OutboxWriter outbox, IdempotencyService idempotency,
                        TransactionTemplate tx, ObjectMapper json) {
        this.clients = clients;
        this.matches = matches;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.tx = tx;
        this.json = json;
    }

    /**
     * 1. claim the Idempotency-Key (platform)
     * 2. fetch the active resume, call ai-service with requestId derived from the key
     * 3. in ONE transaction: save the match, write match.completed, mark the key completed
     * A crash after step 2 is safe: the retry reuses the same requestId, so ai-service returns the
     * stored result instead of paying again.
     */
    public ResponseEntity<MatchResponse> run(long userId, String key, String jobDescription) {
        var req = IdempotentRequest.of("run-match", userId, key, "POST", "/matches",
                Map.of("jobDescription", jobDescription), json);
        return idempotency.execute(req, MatchResponse.class, () -> {
            Clients.ActiveResume resume = clients.activeResume(userId);
            Clients.AiMatch ai = clients.match(userId, "match-" + userId + "-" + key, resume.content(), jobDescription);
            return tx.execute(s -> {
                Match saved = matches.saveAndFlush(new Match(userId, resume.resumeId(), jobDescription,
                        ai.company(), ai.role(), ai.fitScore(), ai.missingSkills(), ai.suggestedBullets(),
                        ai.provider(), ai.model()));
                outbox.write(Topics.MATCH_EVENTS, EventEnvelope.create(EventTypes.MATCH_COMPLETED, userId,
                        "match-" + saved.getId(), 0, Map.of("matchId", saved.getId(), "fitScore", saved.getFitScore()), json));
                MatchResponse body = MatchResponse.of(saved);
                HttpHeaders headers = new HttpHeaders();
                headers.setLocation(URI.create("/matches/" + saved.getId()));
                idempotency.completeInCurrentTransaction(req, 201, headers, body);
                return ResponseEntity.created(headers.getLocation()).body(body);
            });
        });
    }

    @Transactional(readOnly = true)
    public List<Match> list(long userId) {
        return matches.findTop100ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Match get(long userId, long id) {
        return matches.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Match"));
    }
}
```

`config/KafkaTopicsConfig.java`:

```java
package com.aravindakv.hiretrack.match.config;

import com.aravindakv.hiretrack.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {
    @Bean
    NewTopic matchEvents(@Value("${hiretrack.kafka.partitions}") int partitions,
                         @Value("${hiretrack.kafka.replicas}") int replicas) {
        return TopicBuilder.name(Topics.MATCH_EVENTS).partitions(partitions).replicas(replicas).build();
    }
}
```

## Step 6.6 — Controllers

`web/MatchDtos.java`:

```java
package com.aravindakv.hiretrack.match.web;

import com.aravindakv.hiretrack.match.domain.Match;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class MatchDtos {

    private MatchDtos() {}

    public record RunMatch(@NotBlank @Size(max = 20000) String jobDescription) {}

    public record MatchResponse(long id, long resumeId, int fitScore, List<String> missingSkills,
                                List<String> suggestedBullets, String company, String role,
                                String jobDescription, Instant createdAt) {
        public static MatchResponse of(Match m) {
            return new MatchResponse(m.getId(), m.getResumeId(), m.getFitScore(), m.getMissingSkills(),
                    m.getSuggestedBullets(), m.getCompany(), m.getRole(), m.getJobDescription(), m.getCreatedAt());
        }
    }

    public record MatchRef(long id, String company, String role) {}
}
```

`web/MatchController.java`:

```java
package com.aravindakv.hiretrack.match.web;

import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.match.service.MatchService;
import com.aravindakv.hiretrack.match.web.MatchDtos.MatchRef;
import com.aravindakv.hiretrack.match.web.MatchDtos.MatchResponse;
import com.aravindakv.hiretrack.match.web.MatchDtos.RunMatch;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MatchController {

    private final MatchService service;

    public MatchController(MatchService service) {
        this.service = service;
    }

    @PostMapping("/matches")
    ResponseEntity<MatchResponse> run(@CurrentUser long userId, @RequestHeader(IdempotencyService.HEADER) String key,
                                      @Valid @RequestBody RunMatch body) {
        return service.run(userId, key, body.jobDescription());
    }

    @GetMapping("/matches")
    List<MatchResponse> list(@CurrentUser long userId) {
        return service.list(userId).stream().map(MatchResponse::of).toList();
    }

    @GetMapping("/matches/{id}")
    MatchResponse get(@CurrentUser long userId, @PathVariable long id) {
        return MatchResponse.of(service.get(userId, id));
    }

    /** For application-service: confirms a match exists and belongs to the user. */
    @GetMapping("/internal/users/{userId}/matches/{id}")
    MatchRef internal(@PathVariable long userId, @PathVariable long id) {
        var m = service.get(userId, id);
        return new MatchRef(m.getId(), m.getCompany(), m.getRole());
    }
}
```

## Step 6.7 — Run it on its own with fakes

WireMock stubs. `deploy/wiremock/mappings/resume-active.json`:

```json
{
  "request": { "method": "GET", "urlPathPattern": "/internal/users/[0-9]+/active-resume" },
  "response": { "status": 200, "headers": { "Content-Type": "application/json" },
                "jsonBody": { "resumeId": 7, "content": "Java, Spring Boot, PostgreSQL" } }
}
```

`deploy/wiremock/mappings/ai-match.json`:

```json
{
  "request": { "method": "POST", "url": "/internal/match" },
  "response": { "status": 200, "fixedDelayMilliseconds": 1500, "headers": { "Content-Type": "application/json" },
                "jsonBody": { "fitScore": 71, "missingSkills": ["Kafka"], "suggestedBullets": ["b1", "b2", "b3"],
                              "company": "Acme", "role": "Backend Engineer", "provider": "fake", "model": "fake" } }
}
```

```bash
curl -s -X POST localhost:8089/__admin/mappings/reset
RESUME_SERVICE_URL=http://localhost:8089 AI_SERVICE_URL=http://localhost:8089 tools/run-service.sh match-service
```

**Verify**

```bash
TOKEN=$(tools/mint-jwt.sh 1); KEY=$(uuidgen)
for i in 1 2; do
  curl -s -D /tmp/h$i -X POST localhost:8084/matches -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $KEY" -d '{"jobDescription":"Acme hires a Backend Engineer with Java and Kafka"}' | jq -c '{id, fitScore, company}'
done
grep -i idempotent-replayed /tmp/h2
curl -s localhost:8089/__admin/requests/count -d '{"method":"POST","url":"/internal/match"}' | jq .count
```

Expected: the same `{"id":1,"fitScore":71,"company":"Acme"}` twice, `Idempotent-Replayed: true` on the second, and a WireMock count of `1`: the AI was called once.

**Verify with the real services:** run resume-service (Phase 3) and ai-service (Phase 5) too, start match-service without the URL overrides, paste a resume through resume-service, then repeat the call. You get a real score from OpenAI.

## Step 6.8 — Tests

`src/test/java/com/aravindakv/hiretrack/match/MatchIT.java`:

```java
package com.aravindakv.hiretrack.match;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class MatchIT extends IntegrationTest {

    @RegisterExtension
    static WireMockExtension deps = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("hiretrack.clients.resume-url", deps::baseUrl);
        r.add("hiretrack.clients.ai-url", deps::baseUrl);
    }

    @Autowired ObjectMapper json;

    static final String AI_OK = """
            {"fitScore":71,"missingSkills":["Kafka"],"suggestedBullets":["b1","b2","b3"],
             "company":"Acme","role":"Backend Engineer","provider":"openai","model":"test"}""";

    @BeforeEach
    void stubs() {
        deps.resetAll();
        deps.stubFor(get(urlPathMatching("/internal/users/[0-9]+/active-resume")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"resumeId\":7,\"content\":\"Java Spring\"}")));
        deps.stubFor(post("/internal/match").willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(AI_OK)));
    }

    private ResponseEntity<JsonNode> run(long user, String key, String jd) {
        return http.exchange("/matches", HttpMethod.POST,
                new HttpEntity<>(Map.of("jobDescription", jd), authWithKey(user, key)), JsonNode.class);
    }

    @Test
    void runsAMatchAndStoresIt() {
        var res = run(501, newKey(), "Acme hires Java engineers");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("fitScore").asInt()).isEqualTo(71);
        assertThat(res.getBody().get("resumeId").asLong()).isEqualTo(7);
        assertThat(res.getHeaders().getLocation()).hasPath("/matches/" + res.getBody().get("id").asLong());
    }

    @Test
    void retryWithSameKeyReplaysAndCallsAiOnce() {
        String key = newKey();
        var first = run(502, key, "same job");
        var second = run(502, key, "same job");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        deps.verify(1, postRequestedFor(urlEqualTo("/internal/match"))
                .withRequestBody(matchingJsonPath("$.requestId", equalTo("match-502-" + key))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matches WHERE user_id = 502", Integer.class)).isEqualTo(1);
    }

    @Test
    void noActiveResumeIs409() {
        deps.stubFor(get(urlPathMatching("/internal/users/503/active-resume")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"NO_ACTIVE_RESUME\",\"message\":\"Add a resume first.\"}}")));
        var res = run(503, newKey(), "job");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("NO_ACTIVE_RESUME");
    }

    @Test
    void aiFailurePassesThroughAndSavesNothing() {
        deps.stubFor(post("/internal/match").willReturn(aResponse().withStatus(502)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"AI_UNAVAILABLE\",\"message\":\"The AI service didn't respond. Try again.\"}}")));
        String key = newKey();
        var res = run(504, key, "job");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("AI_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matches WHERE user_id = 504", Integer.class)).isZero();

        stubs();                                                       // AI recovers
        assertThat(run(504, key, "job").getStatusCode()).isEqualTo(HttpStatus.CREATED);   // same key works now
    }

    @Test
    void quotaErrorFromAiIs429ForTheBrowser() {
        deps.stubFor(post("/internal/match").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3600")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"MATCH_QUOTA_EXCEEDED\",\"message\":\"You've used today's 20 matches.\"}}")));
        var res = run(505, newKey(), "job");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("3600");
    }

    @Test
    void otherUsersMatchIs404AndInternalLookupRespectsOwner() {
        long id = run(506, newKey(), "job").getBody().get("id").asLong();
        assertThat(http.exchange("/matches/" + id, HttpMethod.GET, new HttpEntity<>(auth(507)), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/internal/users/507/matches/" + id, HttpMethod.GET, new HttpEntity<>(internal()), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/internal/users/506/matches/" + id, HttpMethod.GET, new HttpEntity<>(internal()), JsonNode.class)
                .getBody().get("company").asText()).isEqualTo("Acme");
    }

    @Test
    void overlongJobDescriptionIs400() {
        assertThat(run(508, newKey(), "x".repeat(20001)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void publishesMatchCompleted() {
        long id = run(509, newKey(), "job").getBody().get("id").asLong();
        var events = kafka().readAll("match-events", Duration.ofSeconds(4)).stream()
                .map(r -> EventEnvelope.parse(r.value(), json))
                .filter(e -> e.aggregateId().equals("match-" + id)).toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload().get("fitScore").asInt()).isEqualTo(71);
    }
}
```

**Verify**

```bash
./gradlew :services:match-service:test
```

Expected: 8 tests pass.

---

## Phase demo

Run resume-service, ai-service (real OpenAI key) and match-service, then `tools/demo-phase6.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
TOKEN=$(tools/mint-jwt.sh 800)
curl -s -X POST localhost:8082/resumes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"text":"Frontend developer. React, TypeScript, CSS, Jest. 4 years at a fintech startup."}' >/dev/null
curl -s -X POST localhost:8084/matches -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"jobDescription":"Initrode is hiring a Senior Frontend Engineer: React, TypeScript, GraphQL, accessibility, design systems."}' \
  | jq '{fitScore, missingSkills, company, role, suggestedBullets}'
```

Expected: a real score and output with `company` = `Initrode` and `role` = `Senior Frontend Engineer`. `missingSkills` likely include GraphQL and accessibility.

## Done when

- [ ] 8 tests pass
- [ ] A real end-to-end match works through resume-service, ai-service and OpenAI
- [ ] Retrying with the same key returns the same match, with exactly one AI call
- [ ] No resume → 409; AI errors and quotas pass through with their codes and `Retry-After`
- [ ] Commit: `git commit -am "Phase 6: match-service"`
