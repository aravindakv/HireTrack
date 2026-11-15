package com.aravindakv.hiretrack.feed.web;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.feed.client.ServiceClients;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.service.FeedRuns;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class JobsController {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);
    private static final Set<String> STATUSES = Set.of("NEW", "SAVED", "DISMISSED");

    public record FeedItem(long postingId, String title, String company, String location, String url,
                           Integer salaryMin, Integer salaryMax, Instant postedAt, Integer quickScore,
                           Object missingSkills, String reason, String status, Long applicationId) {}

    public record FeedResponse(List<FeedItem> items, Instant lastRunAt, String lastError, String latestRunStatus) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FeedRuns runs;
    private final ServiceClients clients;
    private final IdempotencyService idempotency;
    private final FeedProperties.Feed props;
    private final ObjectMapper json;
    private final Clock clock;

    public JobsController(JdbcTemplate jdbc, TransactionTemplate tx, FeedRuns runs, ServiceClients clients,
                          IdempotencyService idempotency, FeedProperties props, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.runs = runs;
        this.clients = clients;
        this.idempotency = idempotency;
        this.props = props.feed();
        this.json = json;
        this.clock = clock;
    }

    @GetMapping("/jobs")
    FeedResponse list(@CurrentUser long userId, @RequestParam(defaultValue = "NEW") String status) {
        if (!STATUSES.contains(status)) throw ApiException.badRequest("INVALID_STATUS", "status must be NEW, SAVED or DISMISSED.");
        List<FeedItem> items = jdbc.query("""
                SELECT p.id, p.title, p.company, p.location, p.url, p.salary_min, p.salary_max, p.posted_at,
                       i.fit_score, i.missing_skills::text AS skills, i.reason, i.status, i.application_id
                FROM feed_items i JOIN postings p ON p.id = i.posting_id
                WHERE i.user_id = ? AND i.status = ?
                ORDER BY i.fit_score DESC NULLS LAST, p.posted_at DESC NULLS LAST
                LIMIT 200""",
                (rs, n) -> new FeedItem(rs.getLong("id"), rs.getString("title"), rs.getString("company"),
                        rs.getString("location"), rs.getString("url"), (Integer) rs.getObject("salary_min"),
                        (Integer) rs.getObject("salary_max"),
                        rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toInstant(),
                        (Integer) rs.getObject("fit_score"), readJson(rs.getString("skills")), rs.getString("reason"),
                        rs.getString("status"), (Long) rs.getObject("application_id")),
                userId, status);
        Map<String, Object> profile = jdbc.queryForList(
                "SELECT last_run_at, last_error FROM search_profiles WHERE user_id = ?", userId).stream().findFirst().orElse(Map.of());
        String latest = jdbc.queryForList(
                "SELECT status FROM feed_runs WHERE user_id = ? ORDER BY created_at DESC LIMIT 1", String.class, userId)
                .stream().findFirst().orElse(null);
        Object lastRun = profile.get("last_run_at");
        return new FeedResponse(items, lastRun == null ? null : ((java.sql.Timestamp) lastRun).toInstant(),
                (String) profile.get("last_error"), latest);
    }

    /** Spec 3.6: Refresh now, at most once an hour per user. */
    @PostMapping("/jobs/refresh")
    ResponseEntity<Map> refresh(@CurrentUser long userId, @RequestHeader(IdempotencyService.HEADER) String key) {
        var req = IdempotentRequest.of("feed-refresh", userId, key, "POST", "/jobs/refresh", null, json);
        return idempotency.execute(req, Map.class, () -> tx.execute(s -> {
            Map<String, Object> p = jdbc.queryForList(
                    "SELECT last_manual_run_at FROM search_profiles WHERE user_id = ? FOR UPDATE", userId)
                    .stream().findFirst().orElseThrow(() -> ApiException.conflict("NO_SEARCH_PROFILE", "Set up your job search first."));
            Instant last = p.get("last_manual_run_at") == null ? null : ((java.sql.Timestamp) p.get("last_manual_run_at")).toInstant();
            Instant now = clock.instant();
            if (last != null && last.plus(props.manualRefreshCooldown()).isAfter(now)) {
                long wait = java.time.Duration.between(now, last.plus(props.manualRefreshCooldown())).toSeconds();
                throw ApiException.tooManyRequests("REFRESH_TOO_SOON", "You can refresh once an hour.", (int) Math.max(1, wait));
            }
            UUID runId = runs.request(userId, "manual:" + userId + ":" + HOUR.format(now), "MANUAL");
            jdbc.update("UPDATE search_profiles SET last_manual_run_at = ? WHERE user_id = ?", java.sql.Timestamp.from(now), userId);
            Map<String, Object> body = new HashMap<>();
            body.put("runId", runId == null ? null : runId.toString());
            return ResponseEntity.accepted().body(body);
        }));
    }

    /** Creates a Wishlist application through application-service. Safe to retry at any point. */
    @PostMapping("/jobs/{postingId}/save")
    Map<String, Object> save(@CurrentUser long userId, @PathVariable long postingId) {
        Map<String, Object> item = jdbc.queryForList("""
                SELECT p.title, coalesce(p.company, 'Unknown company') AS company, p.url, i.status, i.application_id
                FROM feed_items i JOIN postings p ON p.id = i.posting_id
                WHERE i.user_id = ? AND i.posting_id = ? AND i.status IN ('NEW', 'SAVED', 'DISMISSED')""", userId, postingId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Posting"));
        if (item.get("application_id") != null) {
            return Map.of("postingId", postingId, "applicationId", item.get("application_id"));
        }
        long applicationId = clients.createApplication(userId, "feed-save-" + userId + "-" + postingId, Map.of(
                "company", truncate((String) item.get("company"), 200),
                "role", truncate((String) item.get("title"), 200),
                "jobUrl", item.get("url"),
                "source", Map.of("type", "FEED", "id", Long.toString(postingId))));
        jdbc.update("UPDATE feed_items SET status = 'SAVED', application_id = ? WHERE user_id = ? AND posting_id = ?",
                applicationId, userId, postingId);
        return Map.of("postingId", postingId, "applicationId", applicationId);
    }

    @PostMapping("/jobs/{postingId}/dismiss")
    ResponseEntity<Void> dismiss(@CurrentUser long userId, @PathVariable long postingId) {
        int n = jdbc.update("""
                UPDATE feed_items SET status = 'DISMISSED'
                WHERE user_id = ? AND posting_id = ? AND status IN ('NEW', 'DISMISSED')""", userId, postingId);
        if (n == 0) throw ApiException.notFound("Posting");
        return ResponseEntity.noContent().build();
    }

    private Object readJson(String s) {
        try { return s == null ? List.of() : json.readValue(s, List.class); } catch (Exception e) { return List.of(); }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}