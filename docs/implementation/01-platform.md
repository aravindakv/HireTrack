# Phase 1 — Platform Libraries and CI

**Goal:** solve the cross-cutting problems once, in shared libraries every service uses:

| Module | Gives every service |
| --- | --- |
| `platform:common-web` | The standard error format, JWT security, `@CurrentUser`, request IDs, internal-call protection, a factory for service-to-service HTTP clients |
| `platform:common-events` | The event envelope, topic names, consumer deduplication, dead-letter handling |
| `platform:outbox` | The transactional outbox: write events in the same transaction as data, relay them to Kafka |
| `platform:idempotency` | `Idempotency-Key` handling for creating `POST` requests |
| `platform:test-support` | Testcontainers, test JWTs and a Kafka probe for integration tests |

Each library is a Spring Boot **auto-configuration**: adding the dependency is enough, no `@Import` needed.

**Time:** 1–2 days.

---

## Step 1.1 — Register the modules

Add to `settings.gradle.kts`:

```kotlin
include(
    "platform:common-web",
    "platform:common-events",
    "platform:outbox",
    "platform:idempotency",
    "platform:test-support",
)
```

```bash
mkdir -p platform/{common-web,common-events,outbox,idempotency,test-support}/src/{main,test}/{java,resources}
```

**Verify**

```bash
./gradlew printModules -q
```

Expected: the five `:platform:*` paths.

---

## Step 1.2 — `platform:test-support`

You build this first, because the other libraries' tests use it.

`platform/test-support/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.library-conventions") }

dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api("org.springframework.security:spring-security-oauth2-jose")
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("org.awaitility:awaitility")
    api("tools.jackson.core:jackson-databind")
    api("org.springframework.boot:spring-boot-resttestclient")   // TestRestTemplate (Spring Boot 4 moved it here)
    api(libs.wiremock)
}
```

### `TestJwt`: signs tokens the way identity-service does

`platform/test-support/src/main/java/com/aravindakv/hiretrack/testsupport/TestJwt.java`:

```java
package com.aravindakv.hiretrack.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

/** One RSA key pair per test JVM, used to sign and verify test tokens. */
public final class TestJwt {

    private static final RSAKey KEY = generate();

    private TestJwt() {}

    public static RSAKey rsaKey() { return KEY; }

    public static RSAPublicKey publicKey() {
        try {
            return KEY.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String token(long userId) {
        return token(userId, "user" + userId + "@example.com", 3600);
    }

    public static String token(long userId, String email, long ttlSeconds) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(Long.toString(userId))
                .claim("email", email)
                .issuer("hiretrack-identity")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

### `TestSecurityConfig`: services trust test tokens

`platform/test-support/src/main/java/com/aravindakv/hiretrack/testsupport/TestSecurityConfig.java`:

```java
package com.aravindakv.hiretrack.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Replaces the key-file/JWKS decoder with one that trusts {@link TestJwt}.
 * @Primary wins over any JwtDecoder a service defines itself (identity-service does).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(TestJwt.publicKey()).build();
    }
}
```

### `ContainersConfig`: real PostgreSQL, Kafka and Redis

`platform/test-support/src/main/java/com/aravindakv/hiretrack/testsupport/ContainersConfig.java`:

```java
package com.aravindakv.hiretrack.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Containers start once per Spring test context and are shared by every test class that
 * uses the same configuration. @ServiceConnection wires their addresses into Spring Boot.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:3.8.0");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}
```

### `KafkaProbe`: read what a service published

`platform/test-support/src/main/java/com/aravindakv/hiretrack/testsupport/KafkaProbe.java`:

```java
package com.aravindakv.hiretrack.testsupport;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads every record on a topic from the beginning. For assertions in integration tests. */
public final class KafkaProbe {

    private final String bootstrapServers;

    public KafkaProbe(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public List<ConsumerRecord<String, String>> readAll(String topic, Duration wait) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(wait);
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        return records;
    }
}
```

### `IntegrationTest`: the base class for service integration tests

`platform/test-support/src/main/java/com/aravindakv/hiretrack/testsupport/IntegrationTest.java`:

```java
package com.aravindakv.hiretrack.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;

import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate          // Spring Boot 4: TestRestTemplate is no longer configured automatically
@Import({ContainersConfig.class, TestSecurityConfig.class})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired protected TestRestTemplate http;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired(required = false) private KafkaContainer kafkaContainer;

    protected HttpHeaders auth(long userId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestJwt.token(userId));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected HttpHeaders authWithKey(long userId, String idempotencyKey) {
        HttpHeaders h = auth(userId);
        h.set("Idempotency-Key", idempotencyKey);
        return h;
    }

    protected HttpHeaders internal() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL_TOKEN);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected KafkaProbe kafka() {
        return new KafkaProbe(kafkaContainer.getBootstrapServers());
    }
}
```

Every service's `src/test/resources/application-test.yml` sets `hiretrack.internal.token: test-internal-token`, so `internal()` headers are accepted.

**Verify**

```bash
./gradlew :platform:test-support:build
```

Expected: `BUILD SUCCESSFUL`.

---

## Step 1.3 — `platform:common-web`

`platform/common-web/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.library-conventions") }

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-restclient")      // RestClient.Builder for InternalRestClients
    api("io.micrometer:micrometer-registry-prometheus")
    api("org.springframework.boot:spring-boot-starter-opentelemetry")   // traces over OTLP
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(project(":platform:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")   // MockMvc
}
```

All classes below go in `platform/common-web/src/main/java/com/aravindakv/hiretrack/common/web/`.

### Error types

`ApiError.java`:

```java
package com.aravindakv.hiretrack.common.web;

public record ApiError(String code, String message) {}
```

`ErrorResponse.java`:

```java
package com.aravindakv.hiretrack.common.web;

public record ErrorResponse(ApiError error) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ApiError(code, message));
    }
}
```

`ApiException.java`: the one exception type services throw for expected failures:

```java
package com.aravindakv.hiretrack.common.web;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Integer retryAfterSeconds;

    public ApiException(int status, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException of(int status, String code, String message) {
        return new ApiException(status, code, message, null);
    }

    public static ApiException badRequest(String code, String message) {
        return of(HttpStatus.BAD_REQUEST.value(), code, message);
    }

    public static ApiException unauthorized(String message) {
        return of(HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED", message);
    }

    public static ApiException notFound(String what) {
        return of(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", what + " not found.");
    }

    public static ApiException conflict(String code, String message) {
        return of(HttpStatus.CONFLICT.value(), code, message);
    }

    public static ApiException conflictRetry(String code, String message, int retryAfterSeconds) {
        return new ApiException(HttpStatus.CONFLICT.value(), code, message, retryAfterSeconds);
    }

    public static ApiException preconditionFailed(String message) {
        return of(HttpStatus.PRECONDITION_FAILED.value(), "PRECONDITION_FAILED", message);
    }

    public static ApiException preconditionRequired(String message) {
        return of(HttpStatus.PRECONDITION_REQUIRED.value(), "PRECONDITION_REQUIRED", message);
    }

    public static ApiException payloadTooLarge(String code, String message) {
        return of(HttpStatus.PAYLOAD_TOO_LARGE.value(), code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY.value(), code, message);
    }

    public static ApiException tooManyRequests(String code, String message, int retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS.value(), code, message, retryAfterSeconds);
    }

    public static ApiException upstream(String code, String message) {
        return of(HttpStatus.BAD_GATEWAY.value(), code, message);
    }

    public int status() { return status; }
    public String code() { return code; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
    public boolean isClientError() { return status >= 400 && status < 500; }
}
```

### Global exception handler

`GlobalExceptionHandler.java`:

```java
package com.aravindakv.hiretrack.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds().toString());
        }
        return builder.body(ErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return status(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> invalidParams(HandlerMethodValidationException e) {
        return status(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more request parameters are invalid.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return status(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "The request body is not valid JSON for this endpoint.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return status(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getName() + " has an invalid value.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        String code = switch (e.getHeaderName()) {
            case "Idempotency-Key" -> "IDEMPOTENCY_KEY_REQUIRED";
            case "If-Match" -> "PRECONDITION_REQUIRED";
            default -> "MISSING_HEADER";
        };
        HttpStatus status = "If-Match".equals(e.getHeaderName()) ? HttpStatus.PRECONDITION_REQUIRED : HttpStatus.BAD_REQUEST;
        return status(status, code, "Header " + e.getHeaderName() + " is required.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return status(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "The file is larger than the allowed size.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return status(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                "This record was changed by another request. Reload it and try again.");
    }

    @ExceptionHandler(ResourceAccessException.class)
    ResponseEntity<ErrorResponse> upstreamUnavailable(ResourceAccessException e) {
        log.warn("Downstream call failed: {}", e.getMessage());
        return status(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", "A dependent service did not respond. Try again.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noRoute(NoResourceFoundException e) {
        return status(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return status(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This method is not supported here.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return status(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side. Try again.");
    }

    private static ResponseEntity<ErrorResponse> status(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
```

### `@CurrentUser`: the user ID always comes from the JWT

`CurrentUser.java`:

```java
package com.aravindakv.hiretrack.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Injects the authenticated user's ID (the JWT "sub" claim) into a controller parameter of type long. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {}
```

`CurrentUserArgumentResolver.java`:

```java
package com.aravindakv.hiretrack.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return parameter.hasParameterAnnotation(CurrentUser.class) && (type == long.class || type == Long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            try {
                return Long.parseLong(jwt.getToken().getSubject());
            } catch (NumberFormatException e) {
                throw ApiException.unauthorized("The access token has an invalid subject.");
            }
        }
        throw ApiException.unauthorized("Log in to continue.");
    }
}
```

### Request IDs

`RequestIdFilter.java`:

```java
package com.aravindakv.hiretrack.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Takes X-Request-Id from the gateway (or creates one), logs it with every line and echoes it back. */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !SAFE.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```

### Internal endpoints: shared-token check

`InternalTokenFilter.java`:

```java
package com.aravindakv.hiretrack.common.web;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requests to /internal/** must carry X-Internal-Token. The gateway never routes these paths,
 * and Kubernetes NetworkPolicies (Phase 13, on AWS) limit who can reach them; this is the third layer.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expected;
    private final ObjectMapper json;

    public InternalTokenFilter(String expectedToken, ObjectMapper json) {
        this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(),
                    ErrorResponse.of("INTERNAL_AUTH_REQUIRED", "This endpoint is for internal services only."));
            return;
        }
        chain.doFilter(request, response);
    }
}
```

### Configuration properties

`HiretrackWebProperties.java`:

```java
package com.aravindakv.hiretrack.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("hiretrack")
public record HiretrackWebProperties(Security security, Internal internal) {

    public HiretrackWebProperties {
        if (security == null) security = new Security(List.of());
        if (internal == null) internal = new Internal(null);
    }

    /** Paths reachable without a JWT, e.g. /auth/login in identity-service. */
    public record Security(List<String> publicPaths) {
        public Security {
            if (publicPaths == null) publicPaths = List.of();
        }
    }

    /** Shared secret for /internal/** calls between services. */
    public record Internal(String token) {}
}
```

### Security and web auto-configuration

`JsonSecurityHandlers.java`:

```java
package com.aravindakv.hiretrack.common.web;

import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes 401/403 in the standard error format instead of Spring's default HTML/empty body. */
final class JsonSecurityHandlers {

    private JsonSecurityHandlers() {}

    static AuthenticationEntryPoint entryPoint(ObjectMapper json) {
        return (request, response, ex) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(),
                    ErrorResponse.of("UNAUTHENTICATED", "Log in to continue. Your session may have expired."));
        };
    }

    static AccessDeniedHandler accessDenied(ObjectMapper json) {
        return (request, response, ex) -> {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(),
                    ErrorResponse.of("FORBIDDEN", "You can't do that."));
        };
    }
}
```

`HiretrackWebAutoConfiguration.java`. These web beans are applied automatically to every servlet service:

```java
package com.aravindakv.hiretrack.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.List;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(HiretrackWebProperties.class)
public class HiretrackWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    @Bean
    WebMvcConfigurer currentUserResolverConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentUserArgumentResolver());
            }
        };
    }
}
```

The security filter chain is **not** an auto-configuration. Spring Boot 4 split its auto-configurations into many modules, and ordering ours before Boot's default security would tie this library to Boot's internal class names. Instead each service opts in with one annotation on its main class. Configuration imported this way is processed before every auto-configuration, so Boot's default security chain backs off.

`EnableHiretrackSecurity.java`:

```java
package com.aravindakv.hiretrack.common.web;

import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Put on every servlet service's @SpringBootApplication class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(HiretrackSecurityConfiguration.class)
public @interface EnableHiretrackSecurity {}
```

`HiretrackSecurityConfiguration.java`:

```java
package com.aravindakv.hiretrack.common.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class HiretrackSecurityConfiguration {

    @Bean
    SecurityFilterChain hiretrackSecurityFilterChain(HttpSecurity http, HiretrackWebProperties props,
                                                     ObjectMapper json) throws Exception {
        String internalToken = props.internal().token();
        if (!StringUtils.hasText(internalToken)) {
            throw new IllegalStateException("hiretrack.internal.token must be set");
        }
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll();
                auth.requestMatchers("/internal/**").permitAll();         // checked by InternalTokenFilter
                props.security().publicPaths().forEach(p -> auth.requestMatchers(p).permitAll());
                auth.requestMatchers("/error").permitAll();
                auth.anyRequest().authenticated();
            })
            .oauth2ResourceServer(o -> o
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(JsonSecurityHandlers.entryPoint(json)))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(JsonSecurityHandlers.entryPoint(json))
                    .accessDeniedHandler(JsonSecurityHandlers.accessDenied(json)))
            .addFilterBefore(new InternalTokenFilter(internalToken, json), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
```

Every service's main class from Phase 2 on carries `@EnableHiretrackSecurity` next to `@SpringBootApplication`. api-gateway doesn't: it's a WebFlux app with its own security (Phase 2).

### Service-to-service HTTP clients

`InternalRestClients.java`:

```java
package com.aravindakv.hiretrack.common.web;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds a RestClient for calling another service's /internal/** API.
 * - Starts from Spring Boot's RestClient.Builder, so calls are traced and timed.
 * - Sends X-Internal-Token and propagates X-Request-Id.
 * - Turns error responses into ApiException with the downstream status and code,
 *   so a 404 or 429 from the callee reaches the browser unchanged.
 */
public final class InternalRestClients {

    private InternalRestClients() {}

    public static RestClient create(RestClient.Builder builder, String baseUrl, String internalToken,
                                    Duration readTimeout, ObjectMapper json) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);

        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(InternalTokenFilter.HEADER, internalToken)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().set(RequestIdFilter.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw toApiException(response, json);
                })
                .build();
    }

    static ApiException toApiException(ClientHttpResponse response, ObjectMapper json) throws IOException {
        int status = response.getStatusCode().value();
        ErrorResponse body = null;
        try {
            body = json.readValue(response.getBody(), ErrorResponse.class);
        } catch (IOException | JacksonException ignored) {
            // not our error format; fall through
        }
        boolean propagate = status < 500 || status == 502 || status == 503;
        if (body != null && body.error() != null && propagate) {
            String retryAfter = response.getHeaders().getFirst("Retry-After");
            Integer retry = retryAfter == null ? null : Integer.valueOf(retryAfter);
            return new ApiException(status, body.error().code(), body.error().message(), retry);
        }
        return ApiException.upstream("UPSTREAM_ERROR", "A dependent service failed. Try again.");
    }
}
```

### Register the auto-configuration

`platform/common-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
com.aravindakv.hiretrack.common.web.HiretrackWebAutoConfiguration
```

### Test it

`platform/common-web/src/test/java/com/aravindakv/hiretrack/common/web/WebTestApp.java`:

```java
package com.aravindakv.hiretrack.common.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
@EnableHiretrackSecurity
public class WebTestApp {

    @RestController
    static class ProbeController {

        @GetMapping("/me")
        Map<String, Long> me(@CurrentUser long userId) {
            return Map.of("userId", userId);
        }

        @GetMapping("/boom")
        void boom() {
            throw ApiException.conflict("THING_TAKEN", "That thing is taken.");
        }

        @PostMapping("/things")
        Map<String, String> create(@Valid @RequestBody Thing thing) {
            return Map.of("name", thing.name());
        }

        @GetMapping("/internal/ping")
        Map<String, String> ping() {
            return Map.of("pong", "yes");
        }
    }

    record Thing(@NotBlank String name) {}
}
```

`platform/common-web/src/test/resources/application.yml`:

```yaml
hiretrack:
  internal:
    token: test-internal-token
```

`platform/common-web/src/test/java/com/aravindakv/hiretrack/common/web/CommonWebTest.java`:

```java
package com.aravindakv.hiretrack.common.web;

import com.aravindakv.hiretrack.testsupport.TestJwt;
import com.aravindakv.hiretrack.testsupport.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = WebTestApp.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class CommonWebTest {

    @Autowired MockMvc mvc;

    @Test
    void missingTokenGives401InStandardFormat() throws Exception {
        mvc.perform(get("/me"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void currentUserComesFromTokenSubject() throws Exception {
        mvc.perform(get("/me").header("Authorization", "Bearer " + TestJwt.token(42)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    void apiExceptionMapsToStatusAndCode() throws Exception {
        mvc.perform(get("/boom").header("Authorization", "Bearer " + TestJwt.token(1)))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error.code").value("THING_TAKEN"))
           .andExpect(jsonPath("$.error.message").value("That thing is taken."));
    }

    @Test
    void validationErrorsAreReadable() throws Exception {
        mvc.perform(post("/things").header("Authorization", "Bearer " + TestJwt.token(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
           .andExpect(jsonPath("$.error.message").value("name must not be blank"));
    }

    @Test
    void internalEndpointNeedsInternalToken() throws Exception {
        mvc.perform(get("/internal/ping"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.error.code").value("INTERNAL_AUTH_REQUIRED"));
        mvc.perform(get("/internal/ping").header("X-Internal-Token", "test-internal-token"))
           .andExpect(status().isOk());
    }

    @Test
    void requestIdIsEchoed() throws Exception {
        mvc.perform(get("/me").header("Authorization", "Bearer " + TestJwt.token(1))
                        .header("X-Request-Id", "req-12345678"))
           .andExpect(header().string("X-Request-Id", "req-12345678"));
    }
}
```

**Verify**

```bash
./gradlew :platform:common-web:test
```

Expected: `BUILD SUCCESSFUL`, 6 tests passed. Open `platform/common-web/build/reports/tests/test/index.html` to see them.

---

## Step 1.4 — `platform:common-events`

`platform/common-events/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.library-conventions") }

dependencies {
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("tools.jackson.core:jackson-databind")          // Jackson 3 (java.time support is built in)
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation(project(":platform:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
```

Classes go in `platform/common-events/src/main/java/com/aravindakv/hiretrack/common/events/`.

`Topics.java`:

```java
package com.aravindakv.hiretrack.common.events;

public final class Topics {
    public static final String IDENTITY_EVENTS = "identity-events";
    public static final String RESUME_EVENTS = "resume-events";
    public static final String MATCH_EVENTS = "match-events";
    public static final String APPLICATION_EVENTS = "application-events";
    public static final String FEED_COMMANDS = "feed-commands";
    public static final String FEED_EVENTS = "feed-events";

    private Topics() {}

    public static String deadLetter(String topic) {
        return topic + ".DLT";
    }
}
```

`EventTypes.java`:

```java
package com.aravindakv.hiretrack.common.events;

public final class EventTypes {
    public static final String USER_REGISTERED = "user.registered";
    public static final String USER_SEEN = "user.seen";
    public static final String RESUME_ACTIVATED = "resume.activated";
    public static final String MATCH_COMPLETED = "match.completed";
    public static final String APPLICATION_CREATED = "application.created";
    public static final String APPLICATION_UPDATED = "application.updated";
    public static final String APPLICATION_STAGE_CHANGED = "application.stage-changed";
    public static final String APPLICATION_DELETED = "application.deleted";
    public static final String INTERVIEW_SCHEDULED = "interview.scheduled";
    public static final String INTERVIEW_UPDATED = "interview.updated";
    public static final String INTERVIEW_DELETED = "interview.deleted";
    public static final String FEED_RUN_REQUESTED = "feed.run-requested";
    public static final String FEED_READY = "feed.ready";

    private EventTypes() {}
}
```

`EventEnvelope.java`:

```java
package com.aravindakv.hiretrack.common.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * The wrapper for every event on Kafka.
 * eventId          unique per event; consumers deduplicate on it
 * userId           also the Kafka record key, so one user's events stay in order on one partition
 * aggregateVersion the owning row's @Version after the change; projections ignore older versions
 */
public record EventEnvelope(
        UUID eventId,
        String type,
        int schemaVersion,
        long userId,
        String aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        JsonNode payload) {

    public static EventEnvelope create(String type, long userId, String aggregateId, long aggregateVersion,
                                       Object payload, ObjectMapper json) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, userId, aggregateId, aggregateVersion,
                Instant.now(), json.valueToTree(payload));
    }

    public <T> T payloadAs(Class<T> type, ObjectMapper json) {
        try {
            return json.treeToValue(payload, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Payload of " + this.type + " is not a " + type.getSimpleName(), e);
        }
    }

    public static EventEnvelope parse(String value, ObjectMapper json) {
        try {
            return json.readValue(value, EventEnvelope.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not an EventEnvelope: " + e.getOriginalMessage(), e);
        }
    }

    public String toJson(ObjectMapper json) {
        try {
            return json.writeValueAsString(this);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`ProcessedEvents.java`:

```java
package com.aravindakv.hiretrack.common.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Consumer-side deduplication. Call inside the same transaction that applies the event:
 * if the transaction rolls back, the event is not marked processed and will be retried.
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true the first time this event ID is seen, false for duplicates. */
    public boolean markProcessed(UUID eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("markProcessed must run inside a transaction");
        }
        return jdbc.update("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 1;
    }
}
```

`EventsAutoConfiguration.java`:

```java
package com.aravindakv.hiretrack.common.events;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * No ordering against Spring Boot's own auto-configurations is needed: these beans only take
 * JdbcTemplate/KafkaTemplate as constructor arguments, and Spring creates those first.
 */
@AutoConfiguration
public class EventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProcessedEvents processedEvents(JdbcTemplate jdbc) {
        return new ProcessedEvents(jdbc);
    }

    /** 3 retries one second apart, then the record goes to <topic>.DLT so the partition keeps moving. */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.deadLetter(record.topic()), -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);   // malformed events: straight to DLT
        return handler;
    }
}
```

`platform/common-events/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
com.aravindakv.hiretrack.common.events.EventsAutoConfiguration
```

Migration, used by every consuming service. `platform/common-events/src/main/resources/db/platform/events/V0_2__processed_events.sql`:

```sql
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX processed_events_processed_at ON processed_events (processed_at);
```

Platform migrations use versions `0.x` so they always run before a service's own `V1__...` migrations.

### Test it

`platform/common-events/src/test/java/com/aravindakv/hiretrack/common/events/EventsTestApp.java`:

```java
package com.aravindakv.hiretrack.common.events;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EventsTestApp {}
```

`platform/common-events/src/test/resources/application.yml`:

```yaml
spring:
  flyway:
    locations: classpath:db/platform/events
```

`platform/common-events/src/test/java/com/aravindakv/hiretrack/common/events/ProcessedEventsIT.java`:

```java
package com.aravindakv.hiretrack.common.events;

import com.aravindakv.hiretrack.testsupport.ContainersConfig;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = EventsTestApp.class)
@Import(ContainersConfig.class)
class ProcessedEventsIT {

    @Autowired ProcessedEvents processed;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    @Test
    void secondDeliveryOfSameEventIsReportedAsDuplicate() {
        UUID id = UUID.randomUUID();
        assertThat(tx.execute(s -> processed.markProcessed(id))).isTrue();
        assertThat(tx.execute(s -> processed.markProcessed(id))).isFalse();
    }

    @Test
    void rolledBackProcessingCanBeRetried() {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            processed.markProcessed(id);
            s.setRollbackOnly();               // the handler failed
        });
        assertThat(tx.execute(s -> processed.markProcessed(id))).isTrue();
    }

    @Test
    void refusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> processed.markProcessed(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void envelopeRoundTripsThroughJson() {
        EventEnvelope e = EventEnvelope.create("demo.happened", 7, "agg-1", 3, Map.of("x", 1), json);
        EventEnvelope back = EventEnvelope.parse(e.toJson(json), json);
        assertThat(back).isEqualTo(e);
        assertThat(back.payload().get("x").asInt()).isEqualTo(1);
    }
}
```

**Verify**

```bash
./gradlew :platform:common-events:test
```

Expected: 4 tests pass. The first run downloads the PostgreSQL and Kafka images, which takes a minute.

---

## Step 1.5 — `platform:outbox`

`platform/outbox/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.library-conventions") }

dependencies {
    api(project(":platform:common-events"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("io.micrometer:micrometer-core")

    testImplementation(project(":platform:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
```

Migration. `platform/outbox/src/main/resources/db/platform/outbox/V0_1__outbox.sql`:

```sql
CREATE TABLE outbox (
    id           UUID        PRIMARY KEY,
    topic        TEXT        NOT NULL,
    record_key   TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;
```

Classes in `platform/outbox/src/main/java/com/aravindakv/hiretrack/outbox/`.

`OutboxProperties.java`:

```java
package com.aravindakv.hiretrack.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hiretrack.outbox")
public record OutboxProperties(Boolean enabled, Long relayDelayMs, Integer batchSize, Integer retentionDays) {
    public OutboxProperties {
        if (enabled == null) enabled = true;
        if (relayDelayMs == null) relayDelayMs = 500L;
        if (batchSize == null) batchSize = 100;
        if (retentionDays == null) retentionDays = 7;
    }
}
```

`OutboxWriter.java`:

```java
package com.aravindakv.hiretrack.outbox;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stores an event in the outbox table as part of the caller's transaction. */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void write(String topic, EventEnvelope event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must join the transaction that changes the data");
        }
        jdbc.update("""
                INSERT INTO outbox (id, topic, record_key, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)""",
                event.eventId(), topic, Long.toString(event.userId()), event.type(), event.toJson(json));
    }
}
```

`OutboxRelay.java`:

```java
package com.aravindakv.hiretrack.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes unpublished outbox rows to Kafka, oldest first.
 * FOR UPDATE SKIP LOCKED lets several pods run the relay without sending a row twice at the same time.
 * If a pod dies after sending but before marking rows published, the rows are sent again:
 * delivery is at-least-once, and consumers deduplicate on eventId.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       OutboxProperties props) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${hiretrack.outbox.relay-delay-ms:500}")
    public void relay() {
        Integer sent;
        do {
            sent = tx.execute(status -> publishBatch());
        } while (sent != null && sent == props.batchSize());
    }

    int publishBatch() {
        List<Row> rows = jdbc.query("""
                SELECT id, topic, record_key, payload::text AS payload
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("record_key"), rs.getString("payload")),
                props.batchSize());
        if (rows.isEmpty()) return 0;

        for (Row row : rows) {
            try {
                kafka.send(row.topic(), row.recordKey(), row.payload()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Outbox publish failed for {}; will retry: {}", row.id(), e.getMessage());
                throw new IllegalStateException("Kafka unavailable", e);   // roll back; rows stay unpublished
            }
        }
        UUID[] ids = rows.stream().map(Row::id).toArray(UUID[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE outbox SET published_at = now() WHERE id = ANY(?)");
            Array array = con.createArrayOf("uuid", ids);
            ps.setArray(1, array);
            return ps;
        });
        return rows.size();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void deletePublishedRows() {
        int deleted = jdbc.update(
                "DELETE FROM outbox WHERE published_at < now() - make_interval(days => ?)", props.retentionDays());
        log.info("Outbox cleanup removed {} rows", deleted);
    }

    record Row(UUID id, String topic, String recordKey, String payload) {}
}
```

`OutboxAutoConfiguration.java`:

```java
package com.aravindakv.hiretrack.outbox;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        return new OutboxWriter(jdbc, json);
    }

    @Bean
    @ConditionalOnProperty(name = "hiretrack.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            OutboxProperties props, MeterRegistry meters) {
        Gauge.builder("hiretrack.outbox.oldest.unpublished", jdbc, j -> {
                    Double seconds = j.queryForObject(
                            "SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) FROM outbox WHERE published_at IS NULL",
                            Double.class);
                    return seconds == null ? 0 : seconds;
                })
                .baseUnit("seconds")
                .description("Age of the oldest event not yet sent to Kafka")
                .register(meters);
        return new OutboxRelay(jdbc, kafka, tx, props);
    }
}
```

`platform/outbox/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
com.aravindakv.hiretrack.outbox.OutboxAutoConfiguration
```

### Test it

`platform/outbox/src/test/java/com/aravindakv/hiretrack/outbox/OutboxTestApp.java`:

```java
package com.aravindakv.hiretrack.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OutboxTestApp {}
```

`platform/outbox/src/test/resources/application.yml`:

```yaml
spring:
  flyway:
    locations: classpath:db/platform/outbox
hiretrack:
  outbox:
    relay-delay-ms: 200
```

`platform/outbox/src/test/java/com/aravindakv/hiretrack/outbox/OutboxIT.java`:

```java
package com.aravindakv.hiretrack.outbox;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.testsupport.ContainersConfig;
import com.aravindakv.hiretrack.testsupport.KafkaProbe;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = OutboxTestApp.class)
@Import(ContainersConfig.class)
class OutboxIT {

    @Autowired OutboxWriter writer;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.testcontainers.kafka.KafkaContainer kafkaContainer;

    @Test
    void committedEventIsPublishedWithUserIdAsKey() {
        EventEnvelope event = EventEnvelope.create("demo.created", 11, "a-1", 0, Map.of("n", 1), json);
        tx.executeWithoutResult(s -> writer.write("outbox-it-commit", event));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?",
                        Boolean.class, event.eventId())).isTrue());

        List<ConsumerRecord<String, String>> records = probe().readAll("outbox-it-commit", Duration.ofSeconds(3));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("11");
        assertThat(EventEnvelope.parse(records.get(0).value(), json).eventId()).isEqualTo(event.eventId());
    }

    @Test
    void rolledBackTransactionPublishesNothing() {
        EventEnvelope event = EventEnvelope.create("demo.created", 12, "a-2", 0, Map.of("n", 2), json);
        tx.executeWithoutResult(s -> {
            writer.write("outbox-it-rollback", event);
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, event.eventId()))
                .isZero();
        assertThat(probe().readAll("outbox-it-rollback", Duration.ofSeconds(3))).isEmpty();
    }

    private KafkaProbe probe() {
        return new KafkaProbe(kafkaContainer.getBootstrapServers());
    }
}
```

**Verify**

```bash
./gradlew :platform:outbox:test
```

Expected: 2 tests pass.

---

## Step 1.6 — `platform:idempotency`

`platform/idempotency/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.library-conventions") }

dependencies {
    api(project(":platform:common-web"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.micrometer:micrometer-core")

    testImplementation(project(":platform:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
```

Migration. `platform/idempotency/src/main/resources/db/platform/idempotency/V0_3__idempotency_keys.sql`:

```sql
CREATE TABLE idempotency_keys (
    user_id          BIGINT      NOT NULL,
    idem_key         TEXT        NOT NULL,
    operation        TEXT        NOT NULL,
    request_hash     TEXT        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status  INT,
    response_headers JSONB,
    response_body    JSONB,
    locked_until     TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);
CREATE INDEX idempotency_keys_expires_at ON idempotency_keys (expires_at);
```

Classes in `platform/idempotency/src/main/java/com/aravindakv/hiretrack/idempotency/`.

`IdempotentRequest.java`:

```java
package com.aravindakv.hiretrack.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identifies one logical user action.
 * operation   a short name for metrics, e.g. "create-application"
 * requestHash SHA-256 of method, path and body: the same key with a different body is rejected
 */
public record IdempotentRequest(String operation, long userId, String key, String requestHash) {

    public static IdempotentRequest of(String operation, long userId, String key,
                                       String method, String path, Object body, ObjectMapper json) {
        try {
            String bodyJson = body == null ? "" : json.writeValueAsString(body);
            return new IdempotentRequest(operation, userId, key,
                    sha256((method + " " + path + "\n" + bodyJson).getBytes(StandardCharsets.UTF_8)));
        } catch (JacksonException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static IdempotentRequest ofBytes(String operation, long userId, String key,
                                            String method, String path, byte[] body) {
        byte[] prefix = (method + " " + path + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(body, 0, all, prefix.length, body.length);
        return new IdempotentRequest(operation, userId, key, sha256(all));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`IdempotencyProperties.java`:

```java
package com.aravindakv.hiretrack.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hiretrack.idempotency")
public record IdempotencyProperties(Integer ttlHours, Integer lockSeconds) {
    public IdempotencyProperties {
        if (ttlHours == null) ttlHours = 24;
        if (lockSeconds == null) lockSeconds = 60;
    }
}
```

`IdempotencyService.java`:

```java
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
```

`IdempotencyAutoConfiguration.java`:

```java
package com.aravindakv.hiretrack.idempotency;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    IdempotencyService idempotencyService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                                          MeterRegistry meters, IdempotencyProperties props) {
        return new IdempotencyService(jdbc, tx, json, meters, props);
    }
}
```

`platform/idempotency/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
com.aravindakv.hiretrack.idempotency.IdempotencyAutoConfiguration
```

### Test it

`platform/idempotency/src/test/java/com/aravindakv/hiretrack/idempotency/IdemTestApp.java`:

```java
package com.aravindakv.hiretrack.idempotency;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IdemTestApp {}
```

`platform/idempotency/src/test/resources/application.yml`:

```yaml
spring:
  flyway:
    locations: classpath:db/platform/idempotency
hiretrack:
  internal:
    token: test-internal-token
  idempotency:
    lock-seconds: 1
```

`platform/idempotency/src/test/java/com/aravindakv/hiretrack/idempotency/IdempotencyServiceIT.java`:

```java
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
```

**Verify**

```bash
./gradlew :platform:idempotency:test
```

Expected: 7 tests pass. Each test maps to one row of the behaviour table in spec 10.6.

---

## Step 1.7 — Service configuration template

Every service from Phase 2 on starts from this `application.yml`. Copy it and replace the placeholders `<service>`, `<schema>` and `<port>`.

```yaml
spring:
  application:
    name: <service>
  threads:
    virtual:
      enabled: true                      # virtual threads for blocking I/O (JDBC, HTTP calls)
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:hiretrack}
    username: ${DB_USER:<schema>_svc}
    password: ${DB_PASSWORD:<schema>_pw}
    hikari:
      schema: <schema>                   # search_path for JPA and JdbcTemplate
      maximum-pool-size: ${DB_POOL_SIZE:10}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none                     # Flyway owns the schema
    properties:
      hibernate:
        default_schema: <schema>
        jdbc.time_zone: UTC
  flyway:
    schemas: <schema>
    default-schema: <schema>
    locations: classpath:db/migration,classpath:db/platform/outbox,classpath:db/platform/events,classpath:db/platform/idempotency
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    producer:
      acks: all
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
    consumer:
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      ack-mode: record
  security:
    oauth2:
      resourceserver:
        jwt:
          public-key-location: file:${HIRETRACK_KEYS_DIR:../../deploy/dev-keys}/public.pem
  lifecycle:
    timeout-per-shutdown-phase: 20s

server:
  port: <port>
  shutdown: graceful

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
  metrics:
    tags:
      service: ${spring.application.name}
      env: ${HIRETRACK_ENV:local}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: ${TRACING_SAMPLE_RATE:1.0}
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTLP_TRACING_ENDPOINT:http://localhost:4318/v1/traces}

logging:
  structured:
    format:
      console: ecs                       # JSON logs with traceId/spanId
  file:
    name: ${HIRETRACK_LOG_DIR:../../logs}/<service>.log
  structured.format.file: ecs

hiretrack:
  internal:
    token: ${HIRETRACK_INTERNAL_TOKEN:dev-internal-token}
  kafka:
    partitions: ${KAFKA_PARTITIONS:3}
    replicas: ${KAFKA_REPLICAS:1}

---
spring:
  config:
    activate:
      on-profile: prod
  security:
    oauth2:
      resourceserver:
        jwt:
          public-key-location:          # unset the dev key file
          jwk-set-uri: ${JWKS_URI:http://identity-service/.well-known/jwks.json}
logging:
  file:
    name:                                 # stdout only in Kubernetes
```

And every service's `src/test/resources/application-test.yml`:

```yaml
hiretrack:
  internal:
    token: test-internal-token
  outbox:
    relay-delay-ms: 100
logging:
  structured:
    format:
      console:                            # plain logs in test output
  file:
    name:
management:
  tracing:
    export:
      enabled: false
```

Remove the `flyway.locations` entries for platform modules a service doesn't use (for example a service with no consumers doesn't need `db/platform/events`).

---

## Step 1.8 — CI pipeline (GitHub Actions)

`.github/workflows/ci.yml`:

```yaml
name: ci

on:
  pull_request:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build, unit and integration tests, coverage gate
        run: ./gradlew build --no-daemon
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: '**/build/reports/tests/test'

  dependency-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Trivy filesystem scan (dependencies and config)
        uses: aquasecurity/trivy-action@0.28.0
        with:
          scan-type: fs
          severity: HIGH,CRITICAL
          exit-code: '1'
          ignore-unfixed: true
```

Phase 11 adds contract tests, image scans and end-to-end jobs. Phase 13 adds image publishing and deployment to AWS.

**Verify**

Push a branch and open a pull request. Both jobs run. Break a test on purpose, push again, and check that the pull request is blocked.

---

## Phase demo

```bash
./gradlew clean :platform:common-web:test :platform:common-events:test :platform:outbox:test :platform:idempotency:test
find platform -path '*/build/test-results/test/*.xml' -exec grep -h -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' {} \;
```

Expected: `BUILD SUCCESSFUL`, and every line shows `failures="0" errors="0"`. Test counts: 6 (web), 4 (events), 2 (outbox), 7 (idempotency).

## Done when

- [ ] All four platform test suites pass
- [ ] The outbox test shows commit → published and rollback → nothing
- [ ] The deduplication test shows a second delivery is skipped
- [ ] Every idempotency behaviour in spec 10.6 has a passing test
- [ ] CI runs on pull requests and blocks merges on failure
- [ ] Commit: `git commit -am "Phase 1: platform libraries"`
