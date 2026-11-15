# Phase 3 — Resume Service

**Goal:** users upload a PDF or paste text. Each upload is a new, immutable resume that becomes the active one. Other services read the active resume through an internal endpoint. Implements spec 3.2.

**Time:** 1–2 days.

**Runs on its own with:** Docker Compose infrastructure (Postgres, Kafka, S3Mock) and a token from `tools/mint-jwt.sh`. No other HireTrack service is needed.

---

## Step 3.1 — Module and build file

Add to `settings.gradle.kts`:

```kotlin
include("services:resume-service")
```

`services/resume-service/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:idempotency"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.pdfbox)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.s3)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
    testImplementation(libs.s3mock.testcontainers)
}
```

`src/main/java/com/aravindakv/hiretrack/resume/ResumeApplication.java`:

```java
package com.aravindakv.hiretrack.resume;

import com.aravindakv.hiretrack.common.web.EnableHiretrackSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@EnableHiretrackSecurity
@ConfigurationPropertiesScan
public class ResumeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResumeApplication.class, args);
    }
}
```

## Step 3.2 — Configuration

`src/main/resources/application.yml`: the Phase 1 template with `<service>` = `resume-service`, `<schema>` = `resume`, `<port>` = `8082`, and flyway locations `classpath:db/migration,classpath:db/platform/outbox,classpath:db/platform/idempotency`. Add:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 3MB

hiretrack:
  resume:
    max-characters: 20000
  storage:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}     # S3Mock locally; leave empty on AWS
    region: ${S3_REGION:us-east-1}
    bucket: ${S3_BUCKET:hiretrack-resumes}
    access-key: ${S3_ACCESS_KEY:hiretrack}            # empty on AWS: IRSA role credentials are used
    secret-key: ${S3_SECRET_KEY:hiretrack-secret}
    path-style: ${S3_PATH_STYLE:true}
    server-side-encryption: ${S3_SSE:false}           # true on AWS
    create-bucket: ${S3_CREATE_BUCKET:true}           # false on AWS (Terraform creates it)
```

`config/ResumeProperties.java`:

```java
package com.aravindakv.hiretrack.resume.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hiretrack")
public record ResumeProperties(Resume resume, Storage storage) {

    public record Resume(int maxCharacters) {}

    public record Storage(String endpoint, String region, String bucket, String accessKey, String secretKey,
                          boolean pathStyle, boolean serverSideEncryption, boolean createBucket) {}
}
```

## Step 3.3 — Migration

`src/main/resources/db/migration/V1__resumes.sql`:

```sql
CREATE TABLE resumes (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    content        TEXT        NOT NULL CHECK (length(content) BETWEEN 1 AND 20000),
    source         TEXT        NOT NULL CHECK (source IN ('PDF', 'PASTE')),
    pdf_object_key TEXT,
    is_active      BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX resumes_user ON resumes (user_id, created_at DESC);
-- Spec 7.2: at most one active resume per user
CREATE UNIQUE INDEX resumes_one_active ON resumes (user_id) WHERE is_active;
```

## Step 3.4 — Domain and repository

`domain/Resume.java`:

```java
package com.aravindakv.hiretrack.resume.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Immutable after creation, except for the active flag. */
@Entity
@Table(name = "resumes")
public class Resume {

    public enum Source { PDF, PASTE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    @Column(name = "pdf_object_key", updatable = false)
    private String pdfObjectKey;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Resume() {}

    public Resume(Long userId, String content, Source source, String pdfObjectKey) {
        this.userId = userId;
        this.content = content;
        this.source = source;
        this.pdfObjectKey = pdfObjectKey;
        this.active = true;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getContent() { return content; }
    public Source getSource() { return source; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`persistence/ResumeRepository.java`:

```java
package com.aravindakv.hiretrack.resume.persistence;

import com.aravindakv.hiretrack.resume.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    List<Resume> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    Optional<Resume> findByUserIdAndActiveTrue(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE resumes SET is_active = false WHERE user_id = :userId AND is_active", nativeQuery = true)
    int deactivateAll(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE resumes SET is_active = true WHERE id = :id AND user_id = :userId", nativeQuery = true)
    int activate(Long id, Long userId);
}
```

## Step 3.5 — PDF text extraction

`service/PdfTextExtractor.java`:

```java
package com.aravindakv.hiretrack.resume.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
public class PdfTextExtractor {

    private static final byte[] MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    public String extract(byte[] bytes) {
        if (bytes.length < MAGIC.length || !Arrays.equals(Arrays.copyOf(bytes, MAGIC.length), MAGIC)) {
            throw ApiException.unprocessable("NOT_A_PDF", "The file is not a PDF.");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw ApiException.unprocessable("PDF_ENCRYPTED", "This PDF is password-protected. Remove the password or paste the text.");
            }
            String text = normalise(new PDFTextStripper().getText(document));
            if (text.isEmpty()) {
                throw ApiException.unprocessable("PDF_HAS_NO_TEXT",
                        "We couldn't read any text in this PDF. It may be a scanned image. Paste the text instead.");
            }
            return text;
        } catch (IOException e) {
            throw ApiException.unprocessable("PDF_UNREADABLE", "This PDF couldn't be opened. Paste the text instead.");
        }
    }

    /** Collapse runs of spaces, keep line breaks, trim. */
    static String normalise(String raw) {
        return raw.replace("\r", "")
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
```

## Step 3.6 — Object storage (S3 / S3Mock)

`config/StorageConfig.java`:

```java
package com.aravindakv.hiretrack.resume.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Configuration
public class StorageConfig {

    @Bean
    S3Client s3Client(ResumeProperties props) {
        ResumeProperties.Storage s = props.storage();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s.region()))
                .forcePathStyle(s.pathStyle());
        if (StringUtils.hasText(s.endpoint())) {
            builder.endpointOverride(URI.create(s.endpoint()));
        }
        builder.credentialsProvider(StringUtils.hasText(s.accessKey())
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(s.accessKey(), s.secretKey()))
                : DefaultCredentialsProvider.create());
        return builder.build();
    }
}
```

`storage/ResumeStorage.java`:

```java
package com.aravindakv.hiretrack.resume.storage;

import com.aravindakv.hiretrack.resume.config.ResumeProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.UUID;

@Component
public class ResumeStorage {

    private static final Logger log = LoggerFactory.getLogger(ResumeStorage.class);

    private final S3Client s3;
    private final ResumeProperties.Storage props;

    public ResumeStorage(S3Client s3, ResumeProperties props) {
        this.s3 = s3;
        this.props = props.storage();
    }

    @PostConstruct
    void createBucketIfAllowed() {
        if (!props.createBucket()) return;
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Created bucket {}", props.bucket());
        }
    }

    /** @return the object key */
    public String storePdf(long userId, byte[] bytes) {
        String key = "resumes/" + userId + "/" + UUID.randomUUID() + ".pdf";
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(props.bucket()).key(key).contentType("application/pdf");
        if (props.serverSideEncryption()) {
            request.serverSideEncryption(ServerSideEncryption.AES256);
        }
        s3.putObject(request.build(), RequestBody.fromBytes(bytes));
        return key;
    }

    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket()).key(key).build());
        } catch (RuntimeException e) {
            log.warn("Could not delete orphaned object {}: {}", key, e.getMessage());
        }
    }
}
```

## Step 3.7 — Resume service

`service/ResumeService.java`:

```java
package com.aravindakv.hiretrack.resume.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import com.aravindakv.hiretrack.resume.config.ResumeProperties;
import com.aravindakv.hiretrack.resume.domain.Resume;
import com.aravindakv.hiretrack.resume.persistence.ResumeRepository;
import com.aravindakv.hiretrack.resume.storage.ResumeStorage;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class ResumeService {

    private final ResumeRepository resumes;
    private final ResumeStorage storage;
    private final PdfTextExtractor pdf;
    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final int maxCharacters;

    public ResumeService(ResumeRepository resumes, ResumeStorage storage, PdfTextExtractor pdf, OutboxWriter outbox,
                         ObjectMapper json, TransactionTemplate tx, ResumeProperties props) {
        this.resumes = resumes;
        this.storage = storage;
        this.pdf = pdf;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
        this.maxCharacters = props.resume().maxCharacters();
    }

    public Resume createFromText(long userId, String text) {
        String content = PdfTextExtractor.normalise(text);
        checkLength(content);
        return withActivationRetry(() -> insertActive(userId, content, Resume.Source.PASTE, null));
    }

    public Resume createFromPdf(long userId, byte[] bytes) {
        String content = pdf.extract(bytes);
        checkLength(content);
        String key = storage.storePdf(userId, bytes);
        try {
            return withActivationRetry(() -> insertActive(userId, content, Resume.Source.PDF, key));
        } catch (RuntimeException e) {
            storage.deleteQuietly(key);       // don't leave an orphaned file when the DB write fails
            throw e;
        }
    }

    public Resume activate(long userId, long resumeId) {
        return withActivationRetry(() -> {
            Resume resume = resumes.findByIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> ApiException.notFound("Resume"));
            if (resume.isActive()) return resume;              // idempotent
            resumes.deactivateAll(userId);
            resumes.activate(resumeId, userId);
            publishActivated(userId, resumeId);
            return resumes.findById(resumeId).orElseThrow();
        });
    }

    @Transactional(readOnly = true)
    public List<Resume> list(long userId) {
        return resumes.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Resume get(long userId, long resumeId) {
        return resumes.findByIdAndUserId(resumeId, userId).orElseThrow(() -> ApiException.notFound("Resume"));
    }

    @Transactional(readOnly = true)
    public Resume active(long userId) {
        return resumes.findByUserIdAndActiveTrue(userId)
                .orElseThrow(() -> ApiException.of(404, "NO_ACTIVE_RESUME", "Add a resume first."));
    }

    private Resume insertActive(long userId, String content, Resume.Source source, String key) {
        resumes.deactivateAll(userId);
        Resume saved = resumes.saveAndFlush(new Resume(userId, content, source, key));
        publishActivated(userId, saved.getId());
        return saved;
    }

    private void publishActivated(long userId, long resumeId) {
        outbox.write(Topics.RESUME_EVENTS, EventEnvelope.create(EventTypes.RESUME_ACTIVATED, userId,
                Long.toString(resumeId), 0, Map.of("resumeId", resumeId), json));
    }

    /**
     * Two requests activating at the same moment: both deactivate, both insert "active", and the
     * partial unique index rejects one. The loser retries once in a fresh transaction and wins.
     */
    private Resume withActivationRetry(Supplier<Resume> work) {
        try {
            return tx.execute(s -> work.get());
        } catch (DataIntegrityViolationException first) {
            return tx.execute(s -> work.get());
        }
    }

    private void checkLength(String content) {
        if (content.isEmpty()) {
            throw ApiException.badRequest("RESUME_EMPTY", "The resume is empty.");
        }
        if (content.length() > maxCharacters) {
            throw ApiException.unprocessable("RESUME_TOO_LONG",
                    "The resume is longer than " + maxCharacters + " characters. Shorten it and try again.");
        }
    }
}
```

`config/KafkaTopicsConfig.java`:

```java
package com.aravindakv.hiretrack.resume.config;

import com.aravindakv.hiretrack.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {
    @Bean
    NewTopic resumeEvents(@Value("${hiretrack.kafka.partitions}") int partitions,
                          @Value("${hiretrack.kafka.replicas}") int replicas) {
        return TopicBuilder.name(Topics.RESUME_EVENTS).partitions(partitions).replicas(replicas).build();
    }
}
```

## Step 3.8 — Controllers

`web/ResumeDtos.java`:

```java
package com.aravindakv.hiretrack.resume.web;

import com.aravindakv.hiretrack.resume.domain.Resume;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ResumeDtos {

    private ResumeDtos() {}

    public record PasteRequest(@NotBlank @Size(max = 20000) String text) {}

    public record ResumeSummary(long id, String source, boolean active, int characters, String preview, Instant createdAt) {
        static ResumeSummary of(Resume r) {
            String preview = r.getContent().length() <= 200 ? r.getContent() : r.getContent().substring(0, 200) + "…";
            return new ResumeSummary(r.getId(), r.getSource().name(), r.isActive(), r.getContent().length(),
                    preview, r.getCreatedAt());
        }
    }

    public record ResumeDetail(long id, String source, boolean active, String content, Instant createdAt) {
        static ResumeDetail of(Resume r) {
            return new ResumeDetail(r.getId(), r.getSource().name(), r.isActive(), r.getContent(), r.getCreatedAt());
        }
    }

    public record ActiveResume(long resumeId, String content) {}
}
```

`web/ResumeController.java`:

```java
package com.aravindakv.hiretrack.resume.web;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import com.aravindakv.hiretrack.resume.service.ResumeService;
import com.aravindakv.hiretrack.resume.web.ResumeDtos.PasteRequest;
import com.aravindakv.hiretrack.resume.web.ResumeDtos.ResumeDetail;
import com.aravindakv.hiretrack.resume.web.ResumeDtos.ResumeSummary;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
public class ResumeController {

    private final ResumeService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public ResumeController(ResumeService service, IdempotencyService idempotency, ObjectMapper json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping("/resumes")
    List<ResumeSummary> list(@CurrentUser long userId) {
        return service.list(userId).stream().map(ResumeSummary::of).toList();
    }

    @GetMapping("/resumes/{id}")
    ResumeDetail get(@CurrentUser long userId, @PathVariable long id) {
        return ResumeDetail.of(service.get(userId, id));
    }

    @PostMapping(value = "/resumes", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ResumeDetail> paste(@CurrentUser long userId,
                                       @RequestHeader(IdempotencyService.HEADER) String key,
                                       @Valid @RequestBody PasteRequest body) {
        var req = IdempotentRequest.of("create-resume", userId, key, "POST", "/resumes", body, json);
        return idempotency.execute(req, ResumeDetail.class,
                () -> ResponseEntity.status(201).body(ResumeDetail.of(service.createFromText(userId, body.text()))));
    }

    @PostMapping(value = "/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ResumeDetail> upload(@CurrentUser long userId,
                                        @RequestHeader(IdempotencyService.HEADER) String key,
                                        @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("FILE_EMPTY", "Choose a PDF file to upload.");
        }
        byte[] bytes = file.getBytes();
        var req = IdempotentRequest.ofBytes("create-resume", userId, key, "POST", "/resumes", bytes);
        return idempotency.execute(req, ResumeDetail.class,
                () -> ResponseEntity.status(201).body(ResumeDetail.of(service.createFromPdf(userId, bytes))));
    }

    @PostMapping("/resumes/{id}/activate")
    ResumeDetail activate(@CurrentUser long userId, @PathVariable long id) {
        return ResumeDetail.of(service.activate(userId, id));
    }
}
```

`web/InternalResumeController.java`:

```java
package com.aravindakv.hiretrack.resume.web;

import com.aravindakv.hiretrack.resume.domain.Resume;
import com.aravindakv.hiretrack.resume.service.ResumeService;
import com.aravindakv.hiretrack.resume.web.ResumeDtos.ActiveResume;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** For match-service and job-feed-service. Guarded by X-Internal-Token (common-web). */
@RestController
public class InternalResumeController {

    private final ResumeService service;

    public InternalResumeController(ResumeService service) {
        this.service = service;
    }

    @GetMapping("/internal/users/{userId}/active-resume")
    ActiveResume active(@PathVariable long userId) {
        Resume r = service.active(userId);
        return new ActiveResume(r.getId(), r.getContent());
    }
}
```

**Verify** (resume-service alone)

```bash
tools/run-service.sh resume-service
```

In another terminal:

```bash
TOKEN=$(tools/mint-jwt.sh 1)
# 1. Paste a resume
curl -s -X POST localhost:8082/resumes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"text":"Jane Doe\nJava, Spring Boot, PostgreSQL, Kafka"}' | jq -c '{id, source, active}'

# 2. Make a small text PDF and upload it
printf '%%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 100]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj 4 0 obj<</Length 44>>stream\nBT /F1 12 Tf 20 50 Td (Java Kafka Docker) Tj ET\nendstream endobj 5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer<</Root 1 0 R>>\n%%%%EOF\n' > /tmp/cv.pdf
curl -s -X POST localhost:8082/resumes -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -F "file=@/tmp/cv.pdf;type=application/pdf" | jq -c '{id, source, active, content}'

# 3. The list shows exactly one active resume
curl -s localhost:8082/resumes -H "Authorization: Bearer $TOKEN" | jq -c '[.[] | {id, active}]'

# 4. Internal endpoint: works with the internal token, refused without
curl -s localhost:8082/internal/users/1/active-resume -H 'X-Internal-Token: dev-internal-token' | jq -c '{resumeId}'
curl -s localhost:8082/internal/users/1/active-resume | jq -c .error.code
```

Expected:

1. `{"id":1,"source":"PASTE","active":true}`
2. `{"id":2,"source":"PDF","active":true,"content":"Java Kafka Docker"}`. PDFBox repairs this hand-made file's missing cross-reference table, so it opens fine.
3. `[{"id":2,"active":true},{"id":1,"active":false}]`
4. `{"resumeId":2}`, then `"INTERNAL_AUTH_REQUIRED"`

```bash
# 5. The PDF is in S3Mock (ListObjectsV2, path-style; S3Mock needs no credentials)
curl -s "localhost:9000/hiretrack-resumes?list-type=2" | grep -o "<Key>[^<]*</Key>"
```

Expected: one `<Key>resumes/1/<uuid>.pdf</Key>` line.

## Step 3.9 — Tests

`src/test/java/com/aravindakv/hiretrack/resume/TestPdfs.java`:

```java
package com.aravindakv.hiretrack.resume;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Builds PDFs in memory so tests don't need fixture files. */
public final class TestPdfs {

    private TestPdfs() {}

    public static byte[] withText(String text) {
        try (PDDocument doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A page with no text layer, like a scanned image. */
    public static byte[] blankPage() {
        try (PDDocument doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`src/test/java/com/aravindakv/hiretrack/resume/service/PdfTextExtractorTest.java`:

```java
package com.aravindakv.hiretrack.resume.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.resume.TestPdfs;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractsTextFromATextPdf() {
        assertThat(extractor.extract(TestPdfs.withText("Java Spring Kafka"))).isEqualTo("Java Spring Kafka");
    }

    @Test
    void scannedPdfIsRejectedWithAHelpfulCode() {
        assertThatThrownBy(() -> extractor.extract(TestPdfs.blankPage()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PDF_HAS_NO_TEXT"));
    }

    @Test
    void nonPdfIsRejectedByMagicBytes() {
        assertThatThrownBy(() -> extractor.extract("hello".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_A_PDF"));
    }

    @Test
    void normaliseCollapsesSpacesButKeepsParagraphs() {
        assertThat(PdfTextExtractor.normalise("  a   b \r\n\n\n\n c  ")).isEqualTo("a b\n\nc");
    }
}
```

`src/test/java/com/aravindakv/hiretrack/resume/ResumeIT.java`:

```java
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
```

**Verify**

```bash
./gradlew :services:resume-service:test
```

Expected: 14 tests pass (4 unit, 10 integration).

---

## Phase demo

`tools/demo-phase3.sh` runs against resume-service alone:

```bash
#!/usr/bin/env bash
set -euo pipefail
SVC=localhost:8082
TOKEN=$(tools/mint-jwt.sh 500)
KEY=$(uuidgen)

echo "1. paste (twice with the same key → one resume)"
curl -s -X POST $SVC/resumes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"text":"Senior Java engineer: Spring Boot, Kafka, PostgreSQL, AWS"}' | jq -c '{id, active}'
curl -s -D - -o /dev/null -X POST $SVC/resumes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"text":"Senior Java engineer: Spring Boot, Kafka, PostgreSQL, AWS"}' | grep -i idempotent-replayed

echo "2. active resume via the internal API"
curl -s $SVC/internal/users/500/active-resume -H 'X-Internal-Token: dev-internal-token' | jq -r .content

echo "3. resume.activated event"
docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic resume-events --from-beginning --timeout-ms 4000 2>/dev/null \
  | jq -c 'select(.userId == 500) | {type, payload}'
```

Expected:

```text
1. paste (twice with the same key → one resume)
{"id":...,"active":true}
Idempotent-Replayed: true
2. active resume via the internal API
Senior Java engineer: Spring Boot, Kafka, PostgreSQL, AWS
3. resume.activated event
{"type":"resume.activated","payload":{"resumeId":...}}
```

## Done when

- [ ] 14 tests pass
- [ ] Text PDFs are accepted; scanned, encrypted, non-PDF and oversized files get clear errors
- [ ] Concurrent uploads leave exactly one active resume
- [ ] The internal endpoint returns the active resume and refuses calls without the internal token
- [ ] The phase demo output matches
- [ ] Commit: `git commit -am "Phase 3: resume-service"`
