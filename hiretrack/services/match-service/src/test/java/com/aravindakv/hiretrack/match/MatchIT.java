package com.aravindakv.hiretrack.match;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("NO_ACTIVE_RESUME");
    }

    @Test
    void aiFailurePassesThroughAndSavesNothing() {
        deps.stubFor(post("/internal/match").willReturn(aResponse().withStatus(502)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"AI_UNAVAILABLE\",\"message\":\"The AI service didn't respond. Try again.\"}}")));
        String key = newKey();
        var res = run(504, key, "job");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("AI_UNAVAILABLE");
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
                .getBody().get("company").asString()).isEqualTo("Acme");
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

    @Test
    void aiSlowerThanTheBudgetFailsWithin16Seconds() {
        deps.stubFor(post("/internal/match").willReturn(aResponse().withFixedDelay(20_000)
                .withHeader("Content-Type", "application/json").withBody(AI_OK)));
        long start = System.nanoTime();
        var res = run(520, newKey(), "job");
        long seconds = java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(seconds).isLessThan(17);                       // ai-timeout 15 s + overhead
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matches WHERE user_id = 520", Integer.class)).isZero();
    }
}