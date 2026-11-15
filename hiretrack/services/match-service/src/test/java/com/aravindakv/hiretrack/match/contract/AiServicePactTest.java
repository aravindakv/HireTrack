package com.aravindakv.hiretrack.match.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.aravindakv.hiretrack.match.client.Clients;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "ai-service", pactVersion = PactSpecVersion.V3)   // V3 builders (PactDslWithProvider); Pact 4.6 defaults to V4
class AiServicePactTest {

    @Pact(consumer = "match-service")
    RequestResponsePact validMatch(PactDslWithProvider builder) {
        return builder
                .given("the AI provider returns a valid match")
                .uponReceiving("a real-time match request")
                    .method("POST").path("/internal/match")
                    .headers("Content-Type", "application/json")   // what RestClient sends (no charset)
                    .matchHeader("X-Internal-Token", ".+", "test-internal-token")
                    .body(new PactDslJsonBody()
                            .integerType("userId", 1)
                            .stringType("requestId", "match-1-key")
                            .stringType("resumeText", "Java Spring")
                            .stringType("jobDescription", "Java role"))
                .willRespondWith()
                    .status(200)
                    .body(new PactDslJsonBody()
                            .integerType("fitScore", 70)
                            .eachLike("missingSkills", PactDslJsonRootValue.stringType("Kafka"), 1)          // array of strings
                            .minArrayLike("suggestedBullets", 3, PactDslJsonRootValue.stringType("bullet"), 3)
                            .stringType("company", "Acme")
                            .stringType("role", "Engineer")
                            .stringType("provider", "openai")
                            .stringType("model", "model-id"))
                .toPact();
    }

    @Pact(consumer = "match-service")
    RequestResponsePact quotaExceeded(PactDslWithProvider builder) {
        return builder
                .given("the user has used today's match quota")
                .uponReceiving("a match request over quota")
                    .method("POST").path("/internal/match")
                    .headers("Content-Type", "application/json")   // what RestClient sends (no charset)
                    .matchHeader("X-Internal-Token", ".+", "test-internal-token")   // match-service always sends it
                    .body(new PactDslJsonBody().integerType("userId", 2).stringType("requestId", "match-2-key")
                            .stringType("resumeText", "Java").stringType("jobDescription", "Java role"))
                .willRespondWith()
                    .status(429)
                    .matchHeader("Retry-After", "\\d+", "3600")
                    .body(new PactDslJsonBody().object("error")
                            .stringValue("code", "MATCH_QUOTA_EXCEEDED").stringType("message", "quota").closeObject().asBody())
                .toPact();
    }

    private Clients clients(MockServer server) {
        return new Clients(RestClient.builder(), JsonMapper.builder().build(), "test-internal-token",
                server.getUrl(), server.getUrl(), Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    @Test
    @PactTestFor(pactMethod = "validMatch")
    void matchServiceReadsTheResponse(MockServer server) {
        var r = clients(server).match(1, "match-1-key", "Java Spring", "Java role");
        assertThat(r.suggestedBullets()).hasSize(3);
        assertThat(r.fitScore()).isBetween(0, 100);
    }

    @Test
    @PactTestFor(pactMethod = "quotaExceeded")
    void quotaErrorKeepsItsCode(MockServer server) {
        try {
            clients(server).match(2, "match-2-key", "Java", "Java role");
        } catch (com.aravindakv.hiretrack.common.web.ApiException e) {
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.code()).isEqualTo("MATCH_QUOTA_EXCEEDED");
            return;
        }
        throw new AssertionError("expected 429");
    }
}