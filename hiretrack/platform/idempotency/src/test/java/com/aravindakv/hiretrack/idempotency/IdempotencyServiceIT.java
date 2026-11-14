package com.aravindakv.hiretrack.idempotency;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.testsupport.ContainersConfig;
import com.aravindakv.hiretrack.testsupport.TestSecurityConfig;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = IdemTestApp.class)
@Import({ContainersConfig.class, TestSecurityConfig.class})
class IdempotencyServiceIT {

    @Autowired IdempotencyService idem;
    @Autowired ObjectMapper json;

    record Body(String name) {}

    private IdempotentRequest req(String key, String name) {
        return IdempotentRequest.of("test-op", 1, key, "POST", "/things", new Body(name), json);
    }

    @Test
    void sameKeyAndBodyRunsTheActionOnceAndReplays() {
        String key = UUID.randomUUID().toString();
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<Body> first = idem.execute(req(key, "a"), Body.class, () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(201).eTag("\"0\"").body(new Body("created-a"));
        });
        ResponseEntity<Body> second = idem.execute(req(key, "a"), Body.class, () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(201).body(new Body("should-not-run"));
        });

        assertThat(calls).hasValue(1);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isEqualTo(new Body("created-a"));
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(first.getBody()).isEqualTo(second.getBody());
    }

    @Test
    void sameKeyDifferentBodyIsRejected() {
        String key = UUID.randomUUID().toString();
        idem.execute(req(key, "a"), Body.class, () -> ResponseEntity.ok(new Body("a")));
        assertThatThrownBy(() -> idem.execute(req(key, "b"), Body.class, () -> ResponseEntity.ok(new Body("b"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
    }

    @Test
    void concurrentDuplicatesRunOnceAndOthersGet409() throws Exception {
        String key = UUID.randomUUID().toString();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<Body>> slow = pool.submit(() -> idem.execute(req(key, "a"), Body.class, () -> {
                calls.incrementAndGet();
                inside.countDown();
                await(release);
                return ResponseEntity.ok(new Body("a"));
            }));
            inside.await();
            assertThatThrownBy(() -> idem.execute(req(key, "a"), Body.class, () -> ResponseEntity.ok(new Body("x"))))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(409);
                        assertThat(e.retryAfterSeconds()).isEqualTo(2);
                    });
            release.countDown();
            assertThat(slow.get().getBody()).isEqualTo(new Body("a"));
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void serverErrorReleasesKeySoRetryRunsAgain() {
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> idem.execute(req(key, "a"), Body.class, () -> {
            throw ApiException.upstream("AI_UNAVAILABLE", "down");
        })).isInstanceOf(ApiException.class);

        ResponseEntity<Body> retry = idem.execute(req(key, "a"), Body.class, () -> ResponseEntity.ok(new Body("ok")));
        assertThat(retry.getBody()).isEqualTo(new Body("ok"));
    }

    @Test
    void clientErrorIsRememberedAndReplayed() {
        String key = UUID.randomUUID().toString();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> idem.execute(req(key, "a"), Body.class, () -> {
                calls.incrementAndGet();
                throw ApiException.badRequest("BAD_THING", "Bad thing.");
            })).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BAD_THING"));
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void staleInProgressKeyCanBeReclaimed() throws Exception {
        String key = UUID.randomUUID().toString();
        // Simulate a crash: claim the key, never complete or release it.
        try {
            idem.execute(req(key, "a"), Body.class, () -> { throw new Error("pod died"); });
        } catch (Error expected) {
            // Errors are not caught by the service, like a JVM crash
        }
        Thread.sleep(1500);   // lock-seconds is 1 in the test config
        ResponseEntity<Body> retry = idem.execute(req(key, "a"), Body.class, () -> ResponseEntity.ok(new Body("ok")));
        assertThat(retry.getBody()).isEqualTo(new Body("ok"));
    }

    @Test
    void malformedKeyIsRejected() {
        assertThatThrownBy(() -> idem.execute(req("short", "a"), Body.class, () -> ResponseEntity.ok(new Body("a"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_INVALID"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}