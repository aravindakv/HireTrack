package com.aravindakv.hiretrack.analytics.service;

import com.aravindakv.hiretrack.analytics.domain.DashboardMath;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DashboardService {

    public record Dashboard(Map<String, Long> byStage, long interviewsThisWeek,
                            long sent, long replied, Double responseRate, String timezone) {}

    private static final String[] STAGES = {"WISHLIST", "APPLIED", "INTERVIEW", "OFFER", "REJECTED"};

    private final JdbcTemplate jdbc;
    private final DashboardCache cache;
    private final ObjectMapper json;
    private final Clock clock;

    public DashboardService(JdbcTemplate jdbc, DashboardCache cache, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.json = json;
        this.clock = clock;
    }

    public Dashboard get(long userId, ZoneId zone) {
        var cached = cache.get(userId, zone.getId());
        if (cached.isPresent()) return read(cached.get());

        Map<String, Long> byStage = new LinkedHashMap<>();
        for (String s : STAGES) byStage.put(s, 0L);
        jdbc.query("SELECT stage, count(*) AS n FROM app_projection WHERE user_id = ? GROUP BY stage",
                rs -> { byStage.put(rs.getString("stage"), rs.getLong("n")); }, userId);

        var week = DashboardMath.currentWeek(clock.instant(), zone);
        Long interviews = jdbc.queryForObject("""
                SELECT count(*) FROM interview_projection
                WHERE user_id = ? AND scheduled_at >= ? AND scheduled_at < ?""",
                Long.class, userId, Timestamp.from(week.from()), Timestamp.from(week.to()));

        Map<String, Object> rates = jdbc.queryForMap("""
                SELECT count(applied_date) AS sent,
                       count(*) FILTER (WHERE applied_date IS NOT NULL AND responded_at IS NOT NULL) AS replied
                FROM app_projection WHERE user_id = ?""", userId);
        long sent = ((Number) rates.get("sent")).longValue();
        long replied = ((Number) rates.get("replied")).longValue();

        Dashboard d = new Dashboard(byStage, interviews == null ? 0 : interviews, sent, replied,
                DashboardMath.responseRate(sent, replied), zone.getId());
        cache.put(userId, zone.getId(), write(d));
        return d;
    }

    private String write(Dashboard d) {
        try { return json.writeValueAsString(d); } catch (JacksonException e) { throw new IllegalStateException(e); }
    }

    private Dashboard read(String s) {
        try { return json.readValue(s, Dashboard.class); } catch (JacksonException e) { throw new IllegalStateException(e); }
    }
}