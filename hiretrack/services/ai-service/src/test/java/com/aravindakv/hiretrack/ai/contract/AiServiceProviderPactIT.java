package com.aravindakv.hiretrack.ai.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.aravindakv.hiretrack.ai.OpenAiStubs;
import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

@Provider("ai-service")
@PactFolder("../../contracts/pacts")
class AiServiceProviderPactIT extends IntegrationTest {

    @RegisterExtension
    static WireMockExtension openai = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("hiretrack.ai.openai.base-url", () -> openai.baseUrl() + "/v1");
    }

    @LocalServerPort int port;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void target(PactVerificationContext ctx) {
        ctx.setTarget(new HttpTestTarget("localhost", port));
        jdbc.update("DELETE FROM ai_requests");                   // contract requestIds are reused between runs
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext ctx) {
        ctx.verifyInteraction();
    }

    @State("the AI provider returns a valid match")
    void validMatch() {
        openai.stubFor(post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(OpenAiStubs.chatCompletion(OpenAiStubs.VALID_MATCH, "stop", null))));
    }

    @State("the user has used today's match quota")
    void quotaUsed() {
        redis.opsForValue().set("ai_quota:2:" + LocalDate.now(ZoneOffset.UTC), "20");
    }
}