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