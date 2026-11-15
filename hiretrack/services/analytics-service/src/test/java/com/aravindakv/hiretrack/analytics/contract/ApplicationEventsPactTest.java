package com.aravindakv.hiretrack.analytics.contract;

import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.Message;
import au.com.dius.pact.core.model.messaging.MessagePact;
import com.aravindakv.hiretrack.common.events.EventEnvelope;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.TimeZone;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "application-service", providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V3)   // V3 MessagePactBuilder
class ApplicationEventsPactTest {

    // Fixed UTC examples: Instant can't parse "+0530", and "now" would change the pact file on every run.
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Pact(consumer = "analytics-service")
    MessagePact stageChanged(MessagePactBuilder builder) {
        return builder.expectsToReceive("an application stage-changed event")
                .withContent(new PactDslJsonBody()
                        .uuid("eventId")
                        .stringValue("type", "application.stage-changed")
                        .integerType("schemaVersion", 1)                    // EventEnvelope needs it
                        .integerType("userId", 1)
                        .stringMatcher("aggregateId", "application-\\d+", "application-5")
                        .integerType("aggregateVersion", 2)
                        .datetime("occurredAt", "yyyy-MM-dd'T'HH:mm:ss.SSSSSSX", Instant.parse("2026-11-15T10:00:00Z"), UTC)
                        .object("payload")
                            .integerType("applicationId", 5)
                            .stringMatcher("stage", "WISHLIST|APPLIED|INTERVIEW|OFFER|REJECTED", "INTERVIEW")
                            .date("appliedDate", "yyyy-MM-dd", java.util.Date.from(Instant.parse("2026-11-10T00:00:00Z")), UTC)
                            .datetime("respondedAt", "yyyy-MM-dd'T'HH:mm:ss.SSSSSSX", Instant.parse("2026-11-14T09:00:00Z"), UTC)
                        .closeObject())
                .toPact();
    }

    @Test
    void analyticsCanParseIt(List<Message> messages) {
        ObjectMapper json = JsonMapper.builder().build();          // Jackson 3 handles java.time itself
        EventEnvelope e = EventEnvelope.parse(messages.get(0).contentsAsString(), json);
        assertThat(e.payload().get("stage").asString()).isNotBlank();
        assertThat(e.aggregateVersion()).isPositive();
    }
}