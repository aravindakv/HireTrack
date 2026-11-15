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
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
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
        // Only count this search: runs planned by earlier tests can still reach the worker during this one.
        deps.verify(1, getRequestedFor(urlPathMatching("/v1/api/jobs/in/search/1"))
                .withQueryParam("what", matching("(?i).*developer.*")));
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