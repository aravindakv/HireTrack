package com.aravindakv.hiretrack.feed.service;

import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.domain.RunSchedule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class FeedPlanner {

    private final JdbcTemplate jdbc;
    private final FeedRuns runs;
    private final FeedProperties.Feed props;
    private final Clock clock;

    public FeedPlanner(JdbcTemplate jdbc, FeedRuns runs, FeedProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.props = props.feed();
        this.clock = clock;
    }

    record Due(long userId, String timezone) {}

    /** Creates today's scheduled run for every due, active user. Running it twice changes nothing. */
    @Transactional
    public int plan() {
        Instant now = clock.instant();
        List<Due> due = jdbc.query("""
                SELECT p.user_id, p.timezone FROM search_profiles p
                JOIN user_activity a ON a.user_id = p.user_id
                WHERE p.enabled AND p.next_run_at <= ? AND a.last_seen_on >= ?
                ORDER BY p.next_run_at
                LIMIT 1000
                FOR UPDATE OF p SKIP LOCKED""",
                (rs, i) -> new Due(rs.getLong("user_id"), rs.getString("timezone")),
                Timestamp.from(now), java.sql.Date.valueOf(LocalDate.now(clock).minusDays(props.activeDays())));
        for (Due d : due) {
            ZoneId zone = ZoneId.of(d.timezone());
            runs.request(d.userId(), "sched:" + d.userId() + ":" + now.atZone(zone).toLocalDate(), "SCHEDULED");
            jdbc.update("UPDATE search_profiles SET next_run_at = ? WHERE user_id = ?",
                    Timestamp.from(RunSchedule.next(now, zone, props.runHour(), d.userId(), props.jitterMinutes())), d.userId());
        }
        // Inactive users keep moving their next_run_at forward so they don't pile up as "due".
        jdbc.update("""
                UPDATE search_profiles SET next_run_at = next_run_at + interval '1 day'
                WHERE enabled AND next_run_at <= ? AND user_id NOT IN
                      (SELECT user_id FROM user_activity WHERE last_seen_on >= ?)""",
                Timestamp.from(now), java.sql.Date.valueOf(LocalDate.now(clock).minusDays(props.activeDays())));
        return due.size();
    }
}