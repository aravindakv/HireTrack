package com.aravindakv.hiretrack.application.service;

import com.aravindakv.hiretrack.application.client.MatchClient;
import com.aravindakv.hiretrack.application.domain.Interview;
import com.aravindakv.hiretrack.application.domain.JobApplication;
import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.domain.Stage;
import com.aravindakv.hiretrack.application.persistence.ApplicationRepository;
import com.aravindakv.hiretrack.application.persistence.InterviewRepository;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class ApplicationService {

    public record NewApplication(String company, String role, String jobUrl, String notes,
                                 SourceType sourceType, String sourceId) {}

    public record Changes(String company, String role, String jobUrl, String notes, Stage stage, LocalDate appliedDate) {}

    public record Created(JobApplication application, boolean isNew) {}

    private final ApplicationRepository applications;
    private final InterviewRepository interviews;
    private final ApplicationEvents events;
    private final MatchClient matches;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, InterviewRepository interviews,
                              ApplicationEvents events, MatchClient matches, TransactionTemplate tx, Clock clock) {
        this.applications = applications;
        this.interviews = interviews;
        this.events = events;
        this.matches = matches;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<JobApplication> list(long userId, Stage stage) {
        return stage == null
                ? applications.findByUserIdOrderByUpdatedAtDesc(userId)
                : applications.findByUserIdAndStageOrderByUpdatedAtDesc(userId, stage);
    }

    @Transactional(readOnly = true)
    public JobApplication get(long userId, long id) {
        return owned(userId, id);
    }

    /**
     * Creates an application. Saving the same match or posting twice returns the existing one
     * (isNew = false) instead of failing: idempotent by design.
     * @param verifySource true for browser requests (check the match belongs to this user);
     *                     false for trusted internal callers (job-feed-service)
     */
    public Created create(long userId, NewApplication req, boolean verifySource) {
        if (verifySource && req.sourceType() == SourceType.MATCH) {
            matches.get(userId, Long.parseLong(req.sourceId()));     // 404 if missing or not owned
        }
        if (verifySource && req.sourceType() == SourceType.FEED) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "Save feed postings from the Job Feed tab.");
        }
        if (req.sourceId() != null) {
            var existing = tx.execute(s -> applications.findByUserIdAndSourceTypeAndSourceId(
                    userId, req.sourceType(), req.sourceId()));
            if (existing.isPresent()) return new Created(existing.get(), false);
        }
        try {
            return tx.execute(s -> {
                JobApplication saved = applications.saveAndFlush(new JobApplication(userId, req.company(), req.role(),
                        req.jobUrl(), req.notes(), req.sourceType(), req.sourceId()));
                events.application(EventTypes.APPLICATION_CREATED, saved, null);
                return new Created(saved, true);
            });
        } catch (DataIntegrityViolationException e) {        // a concurrent save of the same source won
            return tx.execute(s -> new Created(applications.findByUserIdAndSourceTypeAndSourceId(
                    userId, req.sourceType(), req.sourceId()).orElseThrow(() -> e), false));
        }
    }

    @Transactional
    public JobApplication update(long userId, long id, long expectedVersion, Changes c) {
        JobApplication a = owned(userId, id);
        if (a.getVersion() != expectedVersion) {
            throw ApiException.preconditionFailed("This application was changed elsewhere. Reload it and try again.");
        }
        String fromStage = a.getStage().name();
        boolean stageChanged = false;
        if (c.stage() != null) {
            stageChanged = a.moveTo(c.stage(), LocalDate.now(clock), clock.instant());
        }
        if (c.appliedDate() != null) {
            if (a.getAppliedDate() == null) {
                throw ApiException.badRequest("NOT_SENT", "Only a sent application has an applied date.");
            }
            a.correctAppliedDate(c.appliedDate());
        }
        a.edit(c.company(), c.role(), c.jobUrl(), c.notes());
        JobApplication saved = applications.saveAndFlush(a);
        events.application(stageChanged ? EventTypes.APPLICATION_STAGE_CHANGED : EventTypes.APPLICATION_UPDATED,
                saved, stageChanged ? fromStage : null);
        return saved;
    }

    @Transactional
    public void delete(long userId, long id) {
        JobApplication a = owned(userId, id);
        events.application(EventTypes.APPLICATION_DELETED, a, null);
        applications.delete(a);                         // interviews go with it (ON DELETE CASCADE)
    }

    @Transactional(readOnly = true)
    public List<Interview> interviews(long userId, long applicationId) {
        owned(userId, applicationId);
        return interviews.findByApplicationIdOrderByScheduledAtAsc(applicationId);
    }

    @Transactional
    public Interview addInterview(long userId, long applicationId, Instant scheduledAt, String round, String notes) {
        owned(userId, applicationId);
        Interview saved = interviews.saveAndFlush(new Interview(applicationId, scheduledAt, round, notes));
        events.interview(EventTypes.INTERVIEW_SCHEDULED, userId, saved);
        return saved;
    }

    @Transactional
    public Interview editInterview(long userId, long interviewId, Instant scheduledAt, String round, String notes) {
        Interview i = interviews.findOwned(interviewId, userId).orElseThrow(() -> ApiException.notFound("Interview"));
        i.edit(scheduledAt, round, notes);
        Interview saved = interviews.saveAndFlush(i);
        events.interview(EventTypes.INTERVIEW_UPDATED, userId, saved);
        return saved;
    }

    @Transactional
    public void deleteInterview(long userId, long interviewId) {
        Interview i = interviews.findOwned(interviewId, userId).orElseThrow(() -> ApiException.notFound("Interview"));
        events.interview(EventTypes.INTERVIEW_DELETED, userId, i);
        interviews.delete(i);
    }

    private JobApplication owned(long userId, long id) {
        return applications.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Application"));
    }
}