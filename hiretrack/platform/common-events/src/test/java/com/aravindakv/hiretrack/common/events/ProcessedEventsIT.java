package com.aravindakv.hiretrack.common.events;

import com.aravindakv.hiretrack.testsupport.ContainersConfig;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = EventsTestApp.class)
@Import(ContainersConfig.class)
class ProcessedEventsIT {

    @Autowired ProcessedEvents processed;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    @Test
    void secondDeliveryOfSameEventIsReportedAsDuplicate() {
        UUID id = UUID.randomUUID();
        assertThat(tx.<Boolean>execute(s -> processed.markProcessed(id))).isTrue();
        assertThat(tx.<Boolean>execute(s -> processed.markProcessed(id))).isFalse();
    }

    @Test
    void rolledBackProcessingCanBeRetried() {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            processed.markProcessed(id);
            s.setRollbackOnly();               // the handler failed
        });
        assertThat(tx.<Boolean>execute(s -> processed.markProcessed(id))).isTrue();
    }

    @Test
    void refusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> processed.markProcessed(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void envelopeRoundTripsThroughJson() {
        EventEnvelope e = EventEnvelope.create("demo.happened", 7, "agg-1", 3, Map.of("x", 1), json);
        EventEnvelope back = EventEnvelope.parse(e.toJson(json), json);
        assertThat(back).isEqualTo(e);
        assertThat(back.payload().get("x").asInt()).isEqualTo(1);
    }
}