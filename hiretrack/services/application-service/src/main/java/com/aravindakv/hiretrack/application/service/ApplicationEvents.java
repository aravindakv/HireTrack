package com.aravindakv.hiretrack.application.service;

import com.aravindakv.hiretrack.application.domain.Interview;
import com.aravindakv.hiretrack.application.domain.JobApplication;
import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

/** Every application event carries a full snapshot, so consumers never need to call back. */
@Component
public class ApplicationEvents {

    public record ApplicationSnapshot(long applicationId, String company, String role, String stage,
                                      String fromStage, LocalDate appliedDate, Instant respondedAt) {}

    public record InterviewSnapshot(long interviewId, long applicationId, Instant scheduledAt, String round) {}

    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public ApplicationEvents(OutboxWriter outbox, ObjectMapper json, MeterRegistry meters) {
        this.outbox = outbox;
        this.json = json;
        this.meters = meters;
    }

    public void application(String type, JobApplication a, String fromStage) {
        var snapshot = new ApplicationSnapshot(a.getId(), a.getCompany(), a.getRole(), a.getStage().name(),
                fromStage, a.getAppliedDate(), a.getRespondedAt());
        outbox.write(Topics.APPLICATION_EVENTS, EventEnvelope.create(type, a.getUserId(),
                "application-" + a.getId(), a.getVersion(), snapshot, json));
        if (EventTypes.APPLICATION_STAGE_CHANGED.equals(type) && fromStage != null) {   // "Stage changes per day" panel
            meters.counter("hiretrack.applications.stage.changes", "from", fromStage, "to", a.getStage().name()).increment();
        }
    }

    public void interview(String type, long userId, Interview i) {
        var snapshot = new InterviewSnapshot(i.getId(), i.getApplicationId(), i.getScheduledAt(), i.getRound());
        outbox.write(Topics.APPLICATION_EVENTS, EventEnvelope.create(type, userId,
                "interview-" + i.getId(), i.getVersion(), snapshot, json));
    }
}