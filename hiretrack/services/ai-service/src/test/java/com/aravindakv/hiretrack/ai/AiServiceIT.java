package com.aravindakv.hiretrack.ai;

import com.aravindakv.hiretrack.ai.service.FeedBatchService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class AiServiceIT extends IntegrationTest {

    @RegisterExtension
    static WireMockExtension openai = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("hiretrack.ai.openai.base-url", () -> openai.baseUrl() + "/v1");
    }

    @Autowired FeedBatchService batches;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void resetStubs() {
        openai.resetAll();
    }

    private ResponseEntity<JsonNode> match(long userId, String requestId) {
        return http.exchange("/internal/match", HttpMethod.POST, new HttpEntity<>(Map.of(
                "userId", userId, "requestId", requestId,
                "resumeText", "Java Spring", "jobDescription", "Acme wants Java and Kubernetes"), internal()), JsonNode.class);
    }

    private void stubChat(String body) {
        openai.stubFor(post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void matchReturnsValidatedResultAndRecordsUsageOnce() {
        stubChat(OpenAiStubs.chatCompletion(OpenAiStubs.VALID_MATCH, "stop", null));
        String requestId = "req-" + UUID.randomUUID();

        var first = match(301, requestId);
        var second = match(301, requestId);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("fitScore").asInt()).isEqualTo(64);
        assertThat(first.getBody().get("provider").asString()).isEqualTo("openai");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        openai.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));        // paid once

        var row = jdbc.queryForMap("SELECT input_tokens, cached_input_tokens, output_tokens, cost_usd FROM ai_usage WHERE usage_key = ?", requestId);
        assertThat(row.get("input_tokens")).isEqualTo(1200);
        assertThat(row.get("cached_input_tokens")).isEqualTo(200);
        // (1000 × 2 + 200 × 0.5 + 300 × 8) / 1e6 = 0.0045
        assertThat(row.get("cost_usd").toString()).isEqualTo("0.004500");
    }

    @Test
    void refusalIs502AiDeclinedAndNothingIsStored() {
        stubChat(OpenAiStubs.chatCompletion(null, "stop", "I can't help with that."));
        String requestId = "req-" + UUID.randomUUID();
        var res = match(302, requestId);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("AI_DECLINED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_requests WHERE request_id = ?", Integer.class, requestId)).isZero();
    }

    @Test
    void truncatedOutputIs502() {
        stubChat(OpenAiStubs.chatCompletion("{\"fit_score\":5", "length", null));
        assertThat(match(303, "req-" + UUID.randomUUID()).getBody().at("/error/code").asString()).isEqualTo("AI_TRUNCATED");
    }

    @Test
    void providerOutageIs502AfterRetries() {
        openai.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(500)));
        var res = match(304, "req-" + UUID.randomUUID());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("AI_UNAVAILABLE");
        openai.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));        // 1 try + 1 SDK retry
    }

    @Test
    void twentyFirstMatchOfTheDayIs429() {
        stubChat(OpenAiStubs.chatCompletion(OpenAiStubs.VALID_MATCH, "stop", null));
        redis.keys("ai_quota:305:*").forEach(redis::delete);
        for (int i = 0; i < 20; i++) {
            assertThat(match(305, "req-" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        var res = match(305, "req-" + UUID.randomUUID());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("MATCH_QUOTA_EXCEEDED");
    }

    @Test
    void feedBatchSubmitPollAndResults() {
        openai.stubFor(post("/v1/files").willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                {"id":"file-in","object":"file","bytes":100,"created_at":1,"filename":"feed.jsonl","purpose":"batch","status":"processed"}""")));
        openai.stubFor(post("/v1/batches").willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                {"id":"batch_1","object":"batch","endpoint":"/v1/chat/completions","input_file_id":"file-in",
                 "completion_window":"24h","status":"validating","created_at":1}""")));
        openai.stubFor(get("/v1/batches/batch_1").willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                {"id":"batch_1","object":"batch","endpoint":"/v1/chat/completions","input_file_id":"file-in",
                 "completion_window":"24h","status":"completed","created_at":1,"output_file_id":"file-out"}""")));
        String content = "{\\\"scores\\\":[{\\\"posting_id\\\":\\\"p1\\\",\\\"fit_score\\\":81,\\\"missing_skills\\\":[\\\"Go\\\"],\\\"reason\\\":\\\"Strong backend match\\\"}]}";
        openai.stubFor(get("/v1/files/file-out/content").willReturn(aResponse().withBody(
                "{\"custom_id\":\"run-1\",\"response\":{\"status_code\":200,\"body\":{\"model\":\"test-feed-model\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + content + "\",\"refusal\":null}}],\"usage\":{\"prompt_tokens\":2000,\"completion_tokens\":400,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0}}}},\"error\":null}\n")));

        Map<String, Object> body = Map.of("submissionId", "sub-" + UUID.randomUUID(), "items", List.of(Map.of(
                "customId", "run-1", "userId", 306, "resumeText", "Java",
                "postings", List.of(Map.of("postingId", "p1", "title", "Backend Engineer", "company", "Globex", "snippet", "Java APIs")))));
        String batchId = http.exchange("/internal/feed-batches", HttpMethod.POST, new HttpEntity<>(body, internal()), JsonNode.class)
                .getBody().get("batchId").asString();
        String again = http.exchange("/internal/feed-batches", HttpMethod.POST, new HttpEntity<>(body, internal()), JsonNode.class)
                .getBody().get("batchId").asString();
        assertThat(again).isEqualTo(batchId);                                           // same submissionId → same batch
        openai.verify(1, postRequestedFor(urlEqualTo("/v1/batches")));

        batches.pollOpenBatches();

        JsonNode view = http.exchange("/internal/feed-batches/" + batchId, HttpMethod.GET, new HttpEntity<>(internal()), JsonNode.class).getBody();
        assertThat(view.get("status").asString()).isEqualTo("ENDED");
        JsonNode item = view.get("items").get(0);
        assertThat(item.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(item.get("scores").get(0).get("fitScore").asInt()).isEqualTo(81);
        // batch discount applied: (2000 × 1 + 400 × 4) / 1e6 × 0.5 = 0.0018
        assertThat(jdbc.queryForObject("SELECT cost_usd FROM ai_usage WHERE usage_key = ?", String.class, batchId + ":run-1"))
                .isEqualTo("0.001800");
    }
}