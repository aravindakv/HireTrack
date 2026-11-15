package com.aravindakv.hiretrack.feed.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hiretrack.feed.schedulers-enabled", havingValue = "true")
public class FeedSchedulers {

    private final FeedPlanner planner;
    private final BatchSubmitter submitter;
    private final BatchPoller poller;
    private final JdbcTemplate jdbc;

    public FeedSchedulers(FeedPlanner planner, BatchSubmitter submitter, BatchPoller poller, JdbcTemplate jdbc) {
        this.planner = planner;
        this.submitter = submitter;
        this.poller = poller;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "feed-planner", lockAtMostFor = "4m")
    public void plan() { planner.plan(); }

    @Scheduled(fixedDelayString = "${hiretrack.feed.submit-interval:2m}", initialDelayString = "30s")
    @SchedulerLock(name = "feed-submitter", lockAtMostFor = "5m")
    public void submit() { submitter.submit(); }

    @Scheduled(fixedDelayString = "${hiretrack.feed.poll-interval:1m}", initialDelayString = "${hiretrack.feed.poll-interval:1m}")
    @SchedulerLock(name = "feed-poller", lockAtMostFor = "5m")
    public void poll() { poller.poll(); }

    /** Spec 3.6: NEW and DISMISSED items older than 30 days go; SAVED stay. */
    @Scheduled(cron = "0 20 3 * * *")
    @SchedulerLock(name = "feed-cleanup", lockAtMostFor = "30m")
    public void cleanup() {
        jdbc.update("DELETE FROM feed_items WHERE status IN ('NEW', 'DISMISSED', 'FILTERED') AND created_at < now() - interval '30 days'");
        jdbc.update("""
                DELETE FROM postings p WHERE first_seen_at < now() - interval '30 days'
                AND NOT EXISTS (SELECT 1 FROM feed_items i WHERE i.posting_id = p.id)""");
        jdbc.update("DELETE FROM feed_runs WHERE created_at < now() - interval '90 days'");
    }
}