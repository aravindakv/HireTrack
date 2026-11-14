package com.aravindakv.hiretrack.idempotency;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.ErrorResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Makes a creating request safe to retry. See spec 10.6 for the behaviour table.
 *
 *   new key                         → run the action, store the response
 *   same key + body, completed      → stored response, header Idempotent-Replayed: true
 *   same key + body, still running  → 409 REQUEST_IN_PROGRESS, Retry-After: 2
 *   same key, different body        → 422 IDEMPOTENCY_KEY_REUSED
 *   action failed with 5xx          → key released, client may retry with the same key
 *   action failed with 4xx          → stored, the same request gets the same error
 */
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private static final String CLAIM_SQL = """
            INSERT INTO idempotency_keys
                (user_id, idem_key, operation, request_hash, status, locked_until, expires_at)
            VALUES (?, ?, ?, ?, 'IN_PROGRESS', now() + make_interval(secs => ?), now() + make_interval(hours => ?))
            ON CONFLICT (user_id, idem_key) DO UPDATE
               SET status = 'IN_PROGRESS',
                   request_hash = EXCLUDED.request_hash,
                   operation = EXCLUDED.operation,
                   response_status = NULL, response_headers = NULL, response_body = NULL,
                   locked_until = EXCLUDED.locked_until,
                   expires_at = EXCLUDED.expires_at
             WHERE (idempotency_keys.status = 'IN_PROGRESS'
                    AND idempotency_keys.locked_until < now()
                    AND idempotency_keys.request_hash = EXCLUDED.request_hash)
                OR idempotency_keys.expires_at < now()
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final IdempotencyProperties props;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                              MeterRegistry meters, IdempotencyProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
        this.props = props;
    }

    public <T> ResponseEntity<T> execute(IdempotentRequest req, Class<T> bodyType, Supplier<ResponseEntity<T>> action) {
        validateKey(req.key());
        Integer claimed = tx.execute(s -> jdbc.update(CLAIM_SQL, req.userId(), req.key(), req.operation(),
                req.requestHash(), props.lockSeconds(), props.ttlHours()));
        if (claimed == null || claimed == 0) {
            return replayOrReject(req, bodyType);
        }
        count(req, "new");

        ResponseEntity<T> response;
        try {
            response = action.get();
        } catch (ApiException e) {
            if (e.isClientError() && e.status() != 409 && e.status() != 429) {
                storeError(req, e);          // deterministic client errors are remembered
            } else {
                release(req);                // conflicts, rate limits and server errors may succeed later
            }
            throw e;
        } catch (RuntimeException e) {
            release(req);
            throw e;
        }
        tx.executeWithoutResult(s -> complete(req, response.getStatusCode().value(), response.getHeaders(), response.getBody()));
        return response;
    }

    /**
     * Marks the key completed inside the caller's transaction, together with the domain insert and
     * outbox write. Use it when a crash between "data saved" and "response stored" must be impossible.
     * The completion that execute() does afterwards then finds nothing to update.
     */
    public void completeInCurrentTransaction(IdempotentRequest req, int status, HttpHeaders headers, Object body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("completeInCurrentTransaction must run inside a transaction");
        }
        complete(req, status, headers, body);
    }

    private void complete(IdempotentRequest req, int status, HttpHeaders headers, Object body) {
        jdbc.update("""
                UPDATE idempotency_keys
                   SET status = 'COMPLETED', response_status = ?, response_headers = ?::jsonb, response_body = ?::jsonb
                 WHERE user_id = ? AND idem_key = ? AND status = 'IN_PROGRESS'""",
                status, toJson(keptHeaders(headers)), body == null ? null : toJson(body), req.userId(), req.key());
    }

    private void storeError(IdempotentRequest req, ApiException e) {
        tx.executeWithoutResult(s -> complete(req, e.status(), new HttpHeaders(), ErrorResponse.of(e.code(), e.getMessage())));
    }

    private void release(IdempotentRequest req) {
        tx.executeWithoutResult(s -> jdbc.update(
                "DELETE FROM idempotency_keys WHERE user_id = ? AND idem_key = ? AND status = 'IN_PROGRESS'",
                req.userId(), req.key()));
    }

    private <T> ResponseEntity<T> replayOrReject(IdempotentRequest req, Class<T> bodyType) {
        Stored stored;
        try {
            stored = jdbc.queryForObject("""
                    SELECT request_hash, status, response_status, response_headers::text AS h, response_body::text AS b
                      FROM idempotency_keys WHERE user_id = ? AND idem_key = ?""",
                    (rs, i) -> new Stored(rs.getString("request_hash"), rs.getString("status"),
                            (Integer) rs.getObject("response_status"), rs.getString("h"), rs.getString("b")),
                    req.userId(), req.key());
        } catch (EmptyResultDataAccessException e) {
            count(req, "in_progress");
            throw ApiException.conflictRetry("REQUEST_IN_PROGRESS", "This request is already being processed.", 2);
        }
        if (!stored.requestHash().equals(req.requestHash())) {
            count(req, "mismatch");
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request.");
        }
        if ("IN_PROGRESS".equals(stored.status())) {
            count(req, "in_progress");
            throw ApiException.conflictRetry("REQUEST_IN_PROGRESS", "This request is already being processed.", 2);
        }
        count(req, "replayed");
        try {
            if (stored.status() != null && stored.responseStatus() >= 400) {
                ErrorResponse error = json.readValue(stored.body(), ErrorResponse.class);
                throw ApiException.of(stored.responseStatus(), error.error().code(), error.error().message());
            }
            HttpHeaders headers = new HttpHeaders();
            if (stored.headers() != null) {
                Map<String, String> kept = json.readValue(stored.headers(),
                        json.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                kept.forEach(headers::set);
            }
            headers.set(REPLAY_HEADER, "true");
            T body = stored.body() == null ? null : json.readValue(stored.body(), bodyType);
            return ResponseEntity.status(stored.responseStatus()).headers(headers).body(body);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    @Scheduled(cron = "0 15 * * * *")
    public void deleteExpiredKeys() {
        jdbc.update("DELETE FROM idempotency_keys WHERE expires_at < now() - interval '1 hour'");
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 8-100 characters of letters, digits, '-' or '_'.");
        }
    }

    private static Map<String, String> keptHeaders(HttpHeaders headers) {
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : new String[]{HttpHeaders.LOCATION, HttpHeaders.ETAG}) {
            String value = headers.getFirst(name);
            if (value != null) kept.put(name, value);
        }
        return kept;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private void count(IdempotentRequest req, String result) {
        meters.counter("hiretrack.idempotency", "operation", req.operation(), "result", result).increment();
    }

    private record Stored(String requestHash, String status, Integer responseStatus, String headers, String body) {}
}