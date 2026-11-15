package com.aravindakv.hiretrack.ai;

import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import tools.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProviderIT extends IntegrationTest {

    @RegisterExtension
    static WireMockExtension anthropic = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("hiretrack.ai.provider", () -> "anthropic");
        r.add("hiretrack.ai.anthropic.base-url", anthropic::baseUrl);
    }

    @Test
    void sameContractWhenSwitchedToClaude() {
        String text = OpenAiStubs.VALID_MATCH.replace("\"", "\\\"");
        anthropic.stubFor(post("/v1/messages").willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                {"id":"msg_1","type":"message","role":"assistant","model":"test-claude-model",
                 "content":[{"type":"text","text":"%s"}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":900,"output_tokens":250,"cache_read_input_tokens":0}}""".formatted(text))));

        JsonNode res = http.exchange("/internal/match", HttpMethod.POST, new HttpEntity<>(Map.of(
                "userId", 401, "requestId", "claude-1", "resumeText", "Java", "jobDescription", "Java role"), internal()),
                JsonNode.class).getBody();

        assertThat(res.get("provider").asString()).isEqualTo("anthropic");
        assertThat(res.get("fitScore").asInt()).isEqualTo(64);
    }
}