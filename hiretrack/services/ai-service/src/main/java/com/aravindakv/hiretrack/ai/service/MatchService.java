package com.aravindakv.hiretrack.ai.service;

import com.aravindakv.hiretrack.ai.model.MatchResult;
import com.aravindakv.hiretrack.ai.provider.AiProvider;
import com.aravindakv.hiretrack.ai.provider.AiRateLimitedException;
import com.aravindakv.hiretrack.ai.provider.AiRejectedException;
import com.aravindakv.hiretrack.ai.provider.AiUpstreamException;
import com.aravindakv.hiretrack.common.web.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

@Service
public class MatchService {

    public record MatchOutcome(MatchResult result, String provider, String model) {}

    private final AiProvider provider;
    private final MatchQuota quota;
    private final CostCalculator costs;
    private final UsageLedger ledger;
    private final AiMetrics metrics;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Bulkhead bulkhead;
    private final CircuitBreaker breaker;

    public MatchService(AiProvider provider, MatchQuota quota, CostCalculator costs, UsageLedger ledger,
                        AiMetrics metrics, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                        BulkheadRegistry bulkheads, CircuitBreakerRegistry breakers) {
        this.provider = provider;
        this.quota = quota;
        this.costs = costs;
        this.ledger = ledger;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.bulkhead = bulkheads.bulkhead("ai-match");
        this.breaker = breakers.circuitBreaker("ai-provider");
    }

    public MatchOutcome match(long userId, String requestId, String resumeText, String jobDescription) {
        MatchOutcome stored = claimOrReplay(userId, requestId);
        if (stored != null) return stored;

        quota.take(userId);
        Instant start = Instant.now();
        String model = "unknown";
        try {
            AiProvider.MatchCompletion completion = Bulkhead.decorateSupplier(bulkhead,
                    CircuitBreaker.decorateSupplier(breaker, () -> provider.match(resumeText, jobDescription))).get();
            model = completion.usage().model();
            var cost = costs.cost(completion.usage(), false);
            MatchOutcome outcome = new MatchOutcome(completion.result(), provider.name(), model);
            tx.executeWithoutResult(s -> {
                ledger.record(requestId, userId, provider.name(), "MATCH", completion.usage(), cost);
                jdbc.update("UPDATE ai_requests SET status = 'COMPLETED', response = ?::jsonb WHERE request_id = ?",
                        toJson(outcome), requestId);
            });
            metrics.usage(provider.name(), "MATCH", completion.usage(), cost);
            metrics.request(provider.name(), "MATCH", model, "ok");
            return outcome;
        } catch (RuntimeException e) {
            release(userId, requestId);
            throw translate(e, model);
        } finally {
            metrics.latency(provider.name(), "MATCH", model, Duration.between(start, Instant.now()));
        }
    }

    /** Returns the stored outcome for a repeated requestId, null when this call should run. */
    private MatchOutcome claimOrReplay(long userId, String requestId) {
        Integer claimed = tx.execute(s -> jdbc.update("""
                INSERT INTO ai_requests (request_id, user_id, operation, status) VALUES (?, ?, 'MATCH', 'IN_PROGRESS')
                ON CONFLICT (request_id) DO NOTHING""", requestId, userId));
        if (claimed != null && claimed == 1) return null;
        try {
            var row = jdbc.queryForMap("SELECT status, response::text AS response, user_id FROM ai_requests WHERE request_id = ?", requestId);
            if (((Number) row.get("user_id")).longValue() != userId) {
                throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId belongs to another user.");
            }
            if ("IN_PROGRESS".equals(row.get("status"))) {
                throw ApiException.conflictRetry("AI_REQUEST_IN_PROGRESS", "This match is already running.", 2);
            }
            return json.readValue((String) row.get("response"), MatchOutcome.class);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.conflictRetry("AI_REQUEST_IN_PROGRESS", "This match is already running.", 2);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private void release(long userId, String requestId) {
        quota.giveBack(userId);
        tx.executeWithoutResult(s -> jdbc.update(
                "DELETE FROM ai_requests WHERE request_id = ? AND status = 'IN_PROGRESS'", requestId));
    }

    private RuntimeException translate(RuntimeException e, String model) {
        String p = provider.name();
        if (e instanceof ApiException api) return api;
        if (e instanceof BulkheadFullException) {
            metrics.request(p, "MATCH", model, "busy");
            return ApiException.tooManyRequests("AI_BUSY", "Matching is busy right now. Try again in a moment.", 5);
        }
        if (e instanceof CallNotPermittedException) {
            metrics.request(p, "MATCH", model, "circuit_open");
            return ApiException.upstream("AI_UNAVAILABLE", "Matching is temporarily unavailable. Try again shortly.");
        }
        if (e instanceof AiRateLimitedException) {
            metrics.request(p, "MATCH", model, "rate_limited");
            return ApiException.tooManyRequests("AI_RATE_LIMITED", "Matching is busy right now. Try again in a moment.", 10);
        }
        if (e instanceof AiRejectedException r) {
            metrics.request(p, "MATCH", model, r.code().equals("AI_DECLINED") ? "refusal" : "invalid");
            return ApiException.upstream(r.code(), "The AI couldn't produce a valid match for this job. Try again.");
        }
        if (e instanceof AiUpstreamException) {
            metrics.request(p, "MATCH", model, "error");
            return ApiException.upstream("AI_UNAVAILABLE", "The AI service didn't respond. Try again.");
        }
        return e;
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}