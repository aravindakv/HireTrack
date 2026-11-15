package com.aravindakv.hiretrack.resume;

import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import com.adobe.testing.s3mock.testcontainers.S3MockContainer;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeIT extends IntegrationTest {

    static final S3MockContainer S3 = new S3MockContainer("5.2.3");   // same image tag as deploy/docker-compose.yml
    static { S3.start(); }

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry r) {
        // S3Mock accepts any credentials, so the application.yml defaults are fine; the service creates the bucket
        r.add("hiretrack.storage.endpoint", S3::getHttpEndpoint);
    }

    private ResponseEntity<JsonNode> paste(long user, String key, String text) {
        return http.exchange("/resumes", HttpMethod.POST,
                new HttpEntity<>(Map.of("text", text), authWithKey(user, key)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> upload(long user, String key, byte[] pdf) {
        HttpHeaders h = authWithKey(user, key);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(pdf) {
            @Override public String getFilename() { return "cv.pdf"; }
        });
        return http.exchange("/resumes", HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    @Test
    void pastedResumeBecomesActiveAndOlderOneIsDeactivated() {
        paste(101, newKey(), "first resume");
        long second = paste(101, newKey(), "second resume").getBody().get("id").asLong();

        JsonNode list = http.exchange("/resumes", HttpMethod.GET, new HttpEntity<>(auth(101)), JsonNode.class).getBody();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(second);
        assertThat(list.get(0).get("active").asBoolean()).isTrue();
        assertThat(list.get(1).get("active").asBoolean()).isFalse();
    }

    @Test
    void pdfUploadExtractsText() {
        var res = upload(102, newKey(), TestPdfs.withText("Kotlin Gradle Docker"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("content").asString()).isEqualTo("Kotlin Gradle Docker");
        assertThat(res.getBody().get("source").asString()).isEqualTo("PDF");
    }

    @Test
    void scannedPdfGets422() {
        var res = upload(103, newKey(), TestPdfs.blankPage());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("PDF_HAS_NO_TEXT");
    }

    @Test
    void fileOverTwoMegabytesGets413() {
        byte[] big = new byte[2 * 1024 * 1024 + 10];
        System.arraycopy("%PDF-".getBytes(), 0, big, 0, 5);
        assertThat(upload(104, newKey(), big).getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void sameIdempotencyKeyCreatesOneResume() {
        String key = newKey();
        var first = paste(105, key, "same text");
        var second = paste(105, key, "same text");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id = 105", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIs400() {
        var res = http.exchange("/resumes", HttpMethod.POST, new HttpEntity<>(Map.of("text", "x"), auth(106)), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().at("/error/code").asString()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void concurrentUploadsLeaveExactlyOneActiveResume() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .<Callable<Object>>mapToObj(i -> () -> paste(107, newKey(), "resume " + i))
                    .toList();
            pool.invokeAll(tasks);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM resumes WHERE user_id = 107 AND is_active", Integer.class)).isEqualTo(1);
    }

    @Test
    void otherUsersResumeIs404() {
        long id = paste(108, newKey(), "private").getBody().get("id").asLong();
        var res = http.exchange("/resumes/" + id, HttpMethod.GET, new HttpEntity<>(auth(109)), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void internalActiveResumeEndpoint() {
        paste(110, newKey(), "Go Rust");
        var ok = http.exchange("/internal/users/110/active-resume", HttpMethod.GET, new HttpEntity<>(internal()), JsonNode.class);
        assertThat(ok.getBody().get("content").asString()).isEqualTo("Go Rust");

        var none = http.exchange("/internal/users/999/active-resume", HttpMethod.GET, new HttpEntity<>(internal()), JsonNode.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(none.getBody().at("/error/code").asString()).isEqualTo("NO_ACTIVE_RESUME");
    }

    @Test
    void activatingAnOlderResumeSwitchesBack() {
        long first = paste(111, newKey(), "first").getBody().get("id").asLong();
        paste(111, newKey(), "second");
        var res = http.exchange("/resumes/" + first + "/activate", HttpMethod.POST, new HttpEntity<>(auth(111)), JsonNode.class);
        assertThat(res.getBody().get("active").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT id FROM resumes WHERE user_id = 111 AND is_active", Long.class)).isEqualTo(first);
    }
}