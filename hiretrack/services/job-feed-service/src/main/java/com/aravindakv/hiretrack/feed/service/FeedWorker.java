package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.ProcessedEvents;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.feed.client.AdzunaClient.AdzunaPosting;
import com.aravindakv.hiretrack.feed.client.ServiceClients;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.domain.Prefilter;
import com.aravindakv.hiretrack.feed.domain.SearchProfile;
import com.aravindakv.hiretrack.feed.persistence.SearchProfileRepository;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes feed.run-requested. External calls (resume-service, Adzuna) happen outside any DB transaction;
 * all writes for the run then happen in one transaction. A redelivered command finds the run already
 * past REQUESTED and does nothing.
 */
@Component
public class FeedWorker {

    private static final Logger log = LoggerFactory.getLogger(FeedWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ProcessedEvents processed;
    private final SearchProfileRepository profiles;
    private final ServiceClients clients;
    private final PostingFetcher fetcher;
    private final FeedRuns runs;
    private final FeedProperties.Feed props;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public FeedWorker(JdbcTemplate jdbc, TransactionTemplate tx, ProcessedEvents processed, SearchProfileRepository profiles,
                      ServiceClients clients, PostingFetcher fetcher, FeedRuns runs, FeedProperties props,
                      ObjectMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.processed = processed;
        this.profiles = profiles;
        this.clients = clients;
        this.fetcher = fetcher;
        this.runs = runs;
        this.props = props.feed();
        this.json = json;
        this.meters = meters;
    }

    record Run(UUID id, long userId, String trigger, String status) {}

    @KafkaListener(topics = Topics.FEED_COMMANDS, groupId = "job-feed-workers")
    public void on(String value) {
        EventEnvelope e = EventEnvelope.parse(value, json);
        UUID runId = UUID.fromString(e.payload().get("runId").asString());
        Run run = jdbc.query("SELECT id, user_id, trigger, status FROM feed_runs WHERE id = ?",
                rs -> rs.next() ? new Run(runId, rs.getLong("user_id"), rs.getString("trigger"), rs.getString("status")) : null,
                runId);
        if (run == null || !"REQUESTED".equals(run.status())) return;     // already handled

        SearchProfile profile = profiles.findById(run.userId()).orElse(null);
        if (profile == null) {
            tx.executeWithoutResult(s -> runs.finish(runId, run.userId(), "FAILED", "No search profile.", run.trigger()));
            return;
        }
        ServiceClients.ActiveResume resume;
        List<AdzunaPosting> fetched;
        try {
            resume = clients.activeResume(run.userId());
            fetched = fetcher.fetch(profile);
        } catch (ApiException ex) {
            String msg = ex.status() == 404 ? "Add a resume to get job matches." : "Couldn't load your resume. Will retry tomorrow.";
            tx.executeWithoutResult(s -> runs.finish(runId, run.userId(), "FAILED", msg, run.trigger()));
            return;
        } catch (RuntimeException ex) {
            log.warn("Feed run {} failed: {}", runId, ex.getMessage());
            tx.executeWithoutResult(s -> runs.finish(runId, run.userId(), "FAILED",
                    "Job search is unavailable right now. Will retry tomorrow.", run.trigger()));
            return;
        }

        tx.executeWithoutResult(s -> {
            if (!processed.markProcessed(e.eventId())) return;
            List<Prefilter.Candidate> fresh = new ArrayList<>();
            for (AdzunaPosting a : fetched) {
                Long postingId = upsertPosting(a);
                int inserted = jdbc.update("""
                        INSERT INTO feed_items (user_id, posting_id, run_id, resume_id) VALUES (?, ?, ?, ?)
                        ON CONFLICT (user_id, posting_id) DO NOTHING""",
                        run.userId(), postingId, runId, resume.resumeId());
                if (inserted == 1) {
                    fresh.add(new Prefilter.Candidate(postingId, a.title() + " " + a.snippet(),
                            a.postedAt() == null ? 0 : a.postedAt().getEpochSecond()));
                }
            }
            meters.counter("hiretrack.feed.postings", "stage", "new").increment(fresh.size());
            if (fresh.isEmpty()) {
                runs.finish(runId, run.userId(), "NOTHING_NEW", null, run.trigger());
                return;
            }
            List<Prefilter.Ranked> top = Prefilter.top(fresh, resume.content(), props.topN());
            for (Prefilter.Ranked r : top) {
                jdbc.update("UPDATE feed_items SET prefilter_score = ? WHERE user_id = ? AND posting_id = ?",
                        r.score(), run.userId(), r.postingId());
            }
            // Everything that lost the cut is FILTERED: never scored, never "new" again
            jdbc.update("""
                    UPDATE feed_items SET status = 'FILTERED'
                    WHERE run_id = ? AND status = 'PENDING' AND NOT (posting_id = ANY(?))""",
                    runId, top.stream().map(Prefilter.Ranked::postingId).toArray(Long[]::new));
            meters.counter("hiretrack.feed.postings", "stage", "prefiltered").increment(top.size());
            jdbc.update("""
                    UPDATE feed_runs SET status = 'READY_TO_SCORE', resume_id = ?, resume_text = ?, updated_at = now()
                    WHERE id = ? AND status = 'REQUESTED'""", resume.resumeId(), resume.content(), runId);
        });
    }

    private Long upsertPosting(AdzunaPosting a) {
        return jdbc.queryForObject("""
                INSERT INTO postings (source, external_id, title, company, location, url, snippet, salary_min, salary_max, posted_at)
                VALUES ('ADZUNA', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, external_id) DO UPDATE SET title = EXCLUDED.title
                RETURNING id""", Long.class,
                a.externalId(), a.title(), a.company(), a.location(), a.url(), a.snippet(),
                a.salaryMin(), a.salaryMax(), a.postedAt() == null ? null : Timestamp.from(a.postedAt()));
    }
}