# Phase 2 — Identity Service and API Gateway

**Goal:**

- **identity-service** handles signup, login, refresh and logout. It issues 15-minute RS256 access tokens and rotating 7-day refresh tokens in an `HttpOnly` cookie, and publishes its public key as a JWKS.
- **api-gateway** is the only public entry point. It checks the JWT, applies per-user rate limits, adds request IDs and routes `/api/v1/*` to the right service.

**Time:** 2–3 days.

**Runs on its own with:** Docker Compose infrastructure (Postgres, Kafka, Redis). The gateway can be tested against WireMock stand-ins for every backend.

---

## Part A — identity-service

### Step 2.1 — Module and build file

Add to `settings.gradle.kts`:

```kotlin
include("services:identity-service")
```

`services/identity-service/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:outbox"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.security:spring-security-crypto")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
}
```

`services/identity-service/src/main/java/com/aravindakv/hiretrack/identity/IdentityApplication.java`:

```java
package com.aravindakv.hiretrack.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IdentityApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
```

### Step 2.2 — Configuration

`services/identity-service/src/main/resources/application.yml`: the template from Phase 1 step 1.7 with `<service>` = `identity-service`, `<schema>` = `identity`, `<port>` = `8081`, and flyway locations `classpath:db/migration,classpath:db/platform/outbox`. Then add:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

hiretrack:
  security:
    public-paths:
      - /auth/signup
      - /auth/login
      - /auth/refresh
      - /auth/logout
      - /.well-known/jwks.json
  jwt:
    issuer: hiretrack-identity
    key-id: ${JWT_KEY_ID:dev-key-1}
    private-key-location: file:${HIRETRACK_KEYS_DIR:../../deploy/dev-keys}/private.pem
    access-token-ttl: 15m
    refresh-token-ttl: 7d
  auth:
    cookie-name: hiretrack_refresh
    cookie-path: /api/v1/auth          # the path the browser sees through the gateway
    cookie-secure: ${COOKIE_SECURE:false}   # true in prod (HTTPS)
    max-failed-logins: 5
    failed-login-window: 15m
```

`src/test/resources/application-test.yml`: the Phase 1 test template, plus:

```yaml
hiretrack:
  auth:
    cookie-secure: false
```

`IdentityProperties.java` in `config/`:

```java
package com.aravindakv.hiretrack.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("hiretrack")
public record IdentityProperties(Jwt jwt, Auth auth) {

    public record Jwt(String issuer, String keyId, String privateKeyLocation,
                      Duration accessTokenTtl, Duration refreshTokenTtl) {}

    public record Auth(String cookieName, String cookiePath, boolean cookieSecure,
                       int maxFailedLogins, Duration failedLoginWindow) {}
}
```

### Step 2.3 — Database migrations

`src/main/resources/db/migration/V1__users.sql`:

```sql
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    email         TEXT        NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash TEXT        NOT NULL,
    last_seen_on  DATE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`src/main/resources/db/migration/V2__refresh_tokens.sql`:

```sql
CREATE TABLE refresh_tokens (
    id             UUID        PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash     TEXT        NOT NULL UNIQUE,
    family_id      UUID        NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason TEXT CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user ON refresh_tokens (user_id);
```

**Verify**

```bash
tools/run-service.sh identity-service      # leave running in one terminal; Ctrl+C to stop
```

The service fails at start-up until the code below exists. Come back to this check after Step 2.7. Once it starts, run:

```bash
docker compose -f deploy/docker-compose.yml exec -T postgres \
  psql "postgresql://identity_svc:identity_pw@localhost/hiretrack" -c "\dt identity.*"
```

Expected: `users`, `refresh_tokens`, `outbox` and `flyway_schema_history`.

### Step 2.4 — Domain entities and repositories

`domain/User.java`:

```java
package com.aravindakv.hiretrack.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "last_seen_on")
    private LocalDate lastSeenOn;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected User() {}

    public User(String name, String email, String passwordHash) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
}
```

`domain/RefreshToken.java`:

```java
package com.aravindakv.hiretrack.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    public enum RevokedReason { ROTATED, LOGOUT, REUSE_DETECTED }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private RevokedReason revokedReason;

    protected RefreshToken() {}

    public RefreshToken(Long userId, String tokenHash, UUID familyId, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isRevoked() { return revokedAt != null; }

    public void revoke(RevokedReason reason, Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
            revokedReason = reason;
        }
    }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public RevokedReason getRevokedReason() { return revokedReason; }
}
```

`persistence/UserRepository.java`:

```java
package com.aravindakv.hiretrack.identity.persistence;

import com.aravindakv.hiretrack.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Returns 1 the first time a user is seen on a given day, 0 afterwards. */
    @Modifying
    @Query(value = """
            UPDATE users SET last_seen_on = :today
            WHERE id = :userId AND (last_seen_on IS NULL OR last_seen_on <> :today)""", nativeQuery = true)
    int markSeen(Long userId, LocalDate today);
}
```

`persistence/RefreshTokenRepository.java`:

```java
package com.aravindakv.hiretrack.identity.persistence;

import com.aravindakv.hiretrack.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Row lock so two concurrent refreshes with the same token can't both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now, t.revokedReason = :reason
            WHERE t.familyId = :familyId AND t.revokedAt IS NULL""")
    int revokeFamily(UUID familyId, RefreshToken.RevokedReason reason, Instant now);
}
```

### Step 2.5 — Signing key and token services

`config/SigningKeyConfig.java`:

```java
package com.aravindakv.hiretrack.identity.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

@Configuration
public class SigningKeyConfig {

    /** Loads the PKCS#8 PEM private key and derives the public key from it. Tests supply their own key. */
    @Bean
    @Profile("!test")
    RSAKey signingKey(IdentityProperties props, ResourceLoader resources) throws IOException, GeneralSecurityException {
        Resource pem = resources.getResource(props.jwt().privateKeyLocation());
        String body = new String(pem.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(props.jwt().keyId()).build();
    }

    /** identity-service verifies its own tokens with its own key, in every environment. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey) throws JOSEException {
        return NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
    }
}
```

`service/AccessTokenService.java`:

```java
package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.identity.config.IdentityProperties;
import com.aravindakv.hiretrack.identity.domain.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class AccessTokenService {

    private final RSAKey key;
    private final IdentityProperties props;
    private final Clock clock;

    public AccessTokenService(RSAKey key, IdentityProperties props, Clock clock) {
        this.key = key;
        this.props = props;
        this.clock = clock;
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuer(props.jwt().issuer())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.jwt().accessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign access token", e);
        }
    }

    public long ttlSeconds() {
        return props.jwt().accessTokenTtl().toSeconds();
    }

    /** Public half only: safe to publish. */
    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }
}
```

`service/RefreshTokens.java`, the hashing and generation helpers:

```java
package com.aravindakv.hiretrack.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

final class RefreshTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokens() {}

    /** 256 random bits, URL-safe. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Only the hash is stored, so a database leak doesn't leak usable tokens. */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`service/PasswordPolicy.java`:

```java
package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.common.web.ApiException;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    private PasswordPolicy() {}

    /** Spec 3.1: 8-72 bytes. bcrypt ignores everything after 72 bytes, so longer passwords are refused. */
    public static void check(String password) {
        int bytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 8 || bytes > 72) {
            throw ApiException.badRequest("PASSWORD_LENGTH", "Password must be 8 to 72 bytes long.");
        }
    }
}
```

`service/LoginAttempts.java`:

```java
package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.identity.config.IdentityProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Spec 4: 5 failed logins per email per 15 minutes. */
@Component
public class LoginAttempts {

    private final StringRedisTemplate redis;
    private final IdentityProperties props;

    public LoginAttempts(StringRedisTemplate redis, IdentityProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void checkAllowed(String email) {
        String count = redis.opsForValue().get(key(email));
        if (count != null && Integer.parseInt(count) >= props.auth().maxFailedLogins()) {
            throw ApiException.tooManyRequests("TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed logins. Try again in 15 minutes.",
                    (int) props.auth().failedLoginWindow().toSeconds());
        }
    }

    public void recordFailure(String email) {
        Long count = redis.opsForValue().increment(key(email));
        if (count != null && count == 1) {
            redis.expire(key(email), props.auth().failedLoginWindow());
        }
    }

    public void reset(String email) {
        redis.delete(key(email));
    }

    private static String key(String email) {
        return "login_fail:" + email;
    }
}
```

### Step 2.6 — Auth service

`service/AuthService.java`:

```java
package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.identity.config.IdentityProperties;
import com.aravindakv.hiretrack.identity.domain.RefreshToken;
import com.aravindakv.hiretrack.identity.domain.User;
import com.aravindakv.hiretrack.identity.persistence.RefreshTokenRepository;
import com.aravindakv.hiretrack.identity.persistence.UserRepository;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    /** Issued tokens: the raw refresh token goes into the cookie, never into the database. */
    public record Session(User user, String accessToken, long expiresInSeconds, String refreshToken) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenService accessTokens;
    private final PasswordEncoder encoder;
    private final LoginAttempts loginAttempts;
    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final IdentityProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, AccessTokenService accessTokens,
                       PasswordEncoder encoder, LoginAttempts loginAttempts, OutboxWriter outbox, ObjectMapper json,
                       IdentityProperties props, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.encoder = encoder;
        this.loginAttempts = loginAttempts;
        this.outbox = outbox;
        this.json = json;
        this.props = props;
        this.clock = clock;
        this.dummyHash = encoder.encode("timing-equaliser-password");
    }

    public static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public Session signup(String name, String rawEmail, String password) {
        String email = normaliseEmail(rawEmail);
        PasswordPolicy.check(password);
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
        }
        User user;
        try {
            user = users.saveAndFlush(new User(name.trim(), email, encoder.encode(password)));
        } catch (DataIntegrityViolationException e) {           // two signups raced
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
        }
        outbox.write(Topics.IDENTITY_EVENTS, EventEnvelope.create(EventTypes.USER_REGISTERED, user.getId(),
                user.getId().toString(), 0, Map.of("email", email), json));
        markSeen(user);
        return newSession(user, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String rawEmail, String password) {
        String email = normaliseEmail(rawEmail);
        loginAttempts.checkAllowed(email);
        User user = users.findByEmail(email).orElse(null);
        // Always run bcrypt, so response time doesn't reveal whether the email exists.
        boolean ok = encoder.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !ok) {
            loginAttempts.recordFailure(email);
            throw ApiException.of(401, "INVALID_CREDENTIALS", "Email or password is incorrect.");
        }
        loginAttempts.reset(email);
        markSeen(user);
        return newSession(user, UUID.randomUUID());
    }

    /**
     * Rotation: the presented token is revoked and a new one issued in the same family.
     * A token that was already rotated means it was copied: revoke the whole family.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw ApiException.of(401, "REFRESH_TOKEN_MISSING", "Log in to continue.");
        }
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(RefreshTokens.hash(presentedToken))
                .orElseThrow(() -> ApiException.of(401, "REFRESH_TOKEN_INVALID", "Log in to continue."));
        if (token.isRevoked()) {
            if (token.getRevokedReason() == RefreshToken.RevokedReason.ROTATED) {
                refreshTokens.revokeFamily(token.getFamilyId(), RefreshToken.RevokedReason.REUSE_DETECTED, now);
                throw ApiException.of(401, "REFRESH_TOKEN_REUSED",
                        "This session was ended for your security. Log in again.");
            }
            throw ApiException.of(401, "REFRESH_TOKEN_REVOKED", "Log in to continue.");
        }
        if (token.isExpired(now)) {
            throw ApiException.of(401, "REFRESH_TOKEN_EXPIRED", "Your session expired. Log in again.");
        }
        token.revoke(RefreshToken.RevokedReason.ROTATED, now);
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.of(401, "REFRESH_TOKEN_INVALID", "Log in to continue."));
        markSeen(user);
        return newSession(user, token.getFamilyId());
    }

    @Transactional
    public void logout(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) return;
        refreshTokens.findByTokenHash(RefreshTokens.hash(presentedToken)).ifPresent(t ->
                refreshTokens.revokeFamily(t.getFamilyId(), RefreshToken.RevokedReason.LOGOUT, clock.instant()));
    }

    @Transactional(readOnly = true)
    public User me(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    private Session newSession(User user, UUID familyId) {
        String raw = RefreshTokens.newToken();
        refreshTokens.save(new RefreshToken(user.getId(), RefreshTokens.hash(raw), familyId,
                clock.instant().plus(props.jwt().refreshTokenTtl())));
        return new Session(user, accessTokens.issue(user), accessTokens.ttlSeconds(), raw);
    }

    /** At most one user.seen event per user per UTC day; job-feed-service uses it to find active users. */
    private void markSeen(User user) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (users.markSeen(user.getId(), today) == 1) {
            outbox.write(Topics.IDENTITY_EVENTS, EventEnvelope.create(EventTypes.USER_SEEN, user.getId(),
                    user.getId().toString(), 0, Map.of("seenOn", today.toString()), json));
        }
    }
}
```

`config/PasswordConfig.java`:

```java
package com.aravindakv.hiretrack.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

`config/KafkaTopicsConfig.java`:

```java
package com.aravindakv.hiretrack.identity.config;

import com.aravindakv.hiretrack.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {
    @Bean
    NewTopic identityEvents(@Value("${hiretrack.kafka.partitions}") int partitions,
                            @Value("${hiretrack.kafka.replicas}") int replicas) {
        return TopicBuilder.name(Topics.IDENTITY_EVENTS).partitions(partitions).replicas(replicas).build();
    }
}
```

### Step 2.7 — Controllers

`web/AuthDtos.java`:

```java
package com.aravindakv.hiretrack.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record SignupRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank String password) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record UserResponse(long id, String name, String email) {}

    public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {}
}
```

`web/AuthController.java`:

```java
package com.aravindakv.hiretrack.identity.web;

import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.identity.config.IdentityProperties;
import com.aravindakv.hiretrack.identity.domain.User;
import com.aravindakv.hiretrack.identity.service.AccessTokenService;
import com.aravindakv.hiretrack.identity.service.AuthService;
import com.aravindakv.hiretrack.identity.web.AuthDtos.AuthResponse;
import com.aravindakv.hiretrack.identity.web.AuthDtos.LoginRequest;
import com.aravindakv.hiretrack.identity.web.AuthDtos.SignupRequest;
import com.aravindakv.hiretrack.identity.web.AuthDtos.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
public class AuthController {

    private final AuthService auth;
    private final AccessTokenService accessTokens;
    private final IdentityProperties props;

    public AuthController(AuthService auth, AccessTokenService accessTokens, IdentityProperties props) {
        this.auth = auth;
        this.accessTokens = accessTokens;
        this.props = props;
    }

    @PostMapping("/auth/signup")
    ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req) {
        return withCookie(HttpStatus.CREATED, auth.signup(req.name(), req.email(), req.password()));
    }

    @PostMapping("/auth/login")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return withCookie(HttpStatus.OK, auth.login(req.email(), req.password()));
    }

    @PostMapping("/auth/refresh")
    ResponseEntity<AuthResponse> refresh(@CookieValue(name = "${hiretrack.auth.cookie-name}", required = false) String token) {
        return withCookie(HttpStatus.OK, auth.refresh(token));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@CookieValue(name = "${hiretrack.auth.cookie-name}", required = false) String token) {
        auth.logout(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/auth/me")
    UserResponse me(@CurrentUser long userId) {
        return toResponse(auth.me(userId));
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return accessTokens.jwks();
    }

    private ResponseEntity<AuthResponse> withCookie(HttpStatus status, AuthService.Session s) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie(s.refreshToken(), props.jwt().refreshTokenTtl()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthResponse(s.accessToken(), "Bearer", s.expiresInSeconds(), toResponse(s.user())));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(props.auth().cookieName(), value)
                .httpOnly(true)
                .secure(props.auth().cookieSecure())
                .sameSite("Strict")
                .path(props.auth().cookiePath())
                .maxAge(maxAge)
                .build();
    }

    private static UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getName(), u.getEmail());
    }
}
```

**Verify** (run the service on its own)

```bash
tools/run-service.sh identity-service
```

In another terminal:

```bash
# 1. Sign up: 201, an access token, and a Set-Cookie header
curl -si -X POST localhost:8081/auth/signup -H 'Content-Type: application/json' \
  -d '{"name":"Asha","email":"Asha@Example.com","password":"correct-horse"}' \
  -c /tmp/ht-cookies | sed -n '1p;/Set-Cookie/p'

# 2. Use the access token
TOKEN=$(curl -s -X POST localhost:8081/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"correct-horse"}' -c /tmp/ht-cookies | jq -r .accessToken)
curl -s localhost:8081/auth/me -H "Authorization: Bearer $TOKEN" | jq

# 3. JWKS
curl -s localhost:8081/.well-known/jwks.json | jq '.keys[0] | {kty, kid, alg: .alg}'
```

Expected:

1. `HTTP/1.1 201`, and `Set-Cookie: hiretrack_refresh=...; Path=/api/v1/auth; Max-Age=604800; ...; HttpOnly; SameSite=Strict`. The cookie path is `/api/v1/auth` because the browser reaches identity through the gateway. Locally, curl still stores it.
2. `{"id":1,"name":"Asha","email":"asha@example.com"}`. The email is stored lowercased.
3. `{"kty":"RSA","kid":"dev-key-1",...}`

```bash
# 4. The token from mint-jwt.sh is accepted too: same dev key
curl -s localhost:8081/auth/me -H "Authorization: Bearer $(tools/mint-jwt.sh 1)" | jq .email
# 5. user.registered and user.seen reached Kafka
docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic identity-events --from-beginning --timeout-ms 5000 | jq -c '{type, userId}'
```

Expected: `"asha@example.com"`, then `{"type":"user.registered","userId":1}` and `{"type":"user.seen","userId":1}`.

### Step 2.8 — identity-service tests

`src/test/java/com/aravindakv/hiretrack/identity/IdentityTestConfig.java`:

```java
package com.aravindakv.hiretrack.identity;

import com.aravindakv.hiretrack.testsupport.TestJwt;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** identity-service signs with the same key the tests' JwtDecoder trusts. */
@TestConfiguration(proxyBeanMethods = false)
public class IdentityTestConfig {
    @Bean
    RSAKey signingKey() {
        return TestJwt.rsaKey();
    }
}
```

`src/test/java/com/aravindakv/hiretrack/identity/service/PasswordPolicyTest.java`:

```java
package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void acceptsEightToSeventyTwoBytes() {
        assertThatCode(() -> PasswordPolicy.check("12345678")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.check("a".repeat(72))).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortAndOverlongPasswords() {
        assertThatThrownBy(() -> PasswordPolicy.check("1234567")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("a".repeat(73))).isInstanceOf(ApiException.class);
        // 25 three-byte characters = 75 bytes: too long even though it's only 25 characters
        assertThatThrownBy(() -> PasswordPolicy.check("€".repeat(25))).isInstanceOf(ApiException.class);
    }
}
```

`src/test/java/com/aravindakv/hiretrack/identity/AuthFlowIT.java`:

```java
package com.aravindakv.hiretrack.identity;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(IdentityTestConfig.class)
class AuthFlowIT extends IntegrationTest {

    @Autowired ObjectMapper json;

    private String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@Example.com";
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) h.add(HttpHeaders.COOKIE, cookie);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    private static String refreshCookie(ResponseEntity<?> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.substring(0, setCookie.indexOf(';'));       // "hiretrack_refresh=<value>"
    }

    @Test
    void signupLoginAndMe() {
        String email = uniqueEmail();
        var signup = post("/auth/signup", Map.of("name", "Asha", "email", email, "password", "correct-horse"), null);
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(signup.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1/auth");

        String token = signup.getBody().get("accessToken").asText();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        var me = http.exchange("/auth/me", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(me.getBody().get("email").asText()).isEqualTo(email.toLowerCase());
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        String email = uniqueEmail();
        post("/auth/signup", Map.of("name", "A", "email", email, "password", "correct-horse"), null);
        var again = post("/auth/signup", Map.of("name", "B", "email", email.toUpperCase(), "password", "correct-horse"), null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().at("/error/code").asText()).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void sixthFailedLoginWithinWindowIsRateLimited() {
        String email = uniqueEmail();
        post("/auth/signup", Map.of("name", "A", "email", email, "password", "correct-horse"), null);
        for (int i = 0; i < 5; i++) {
            assertThat(post("/auth/login", Map.of("email", email, "password", "wrong-password"), null)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var sixth = post("/auth/login", Map.of("email", email, "password", "correct-horse"), null);
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getHeaders().getFirst("Retry-After")).isEqualTo("900");
    }

    @Test
    void refreshRotatesAndReuseRevokesTheFamily() {
        String email = uniqueEmail();
        var signup = post("/auth/signup", Map.of("name", "A", "email", email, "password", "correct-horse"), null);
        String first = refreshCookie(signup);

        var refreshed = post("/auth/refresh", null, first);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = refreshCookie(refreshed);
        assertThat(second).isNotEqualTo(first);

        var reuse = post("/auth/refresh", null, first);           // stolen old token
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody().at("/error/code").asText()).isEqualTo("REFRESH_TOKEN_REUSED");

        var afterReuse = post("/auth/refresh", null, second);     // the legitimate one is dead too
        assertThat(afterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutEndsTheSession() {
        String email = uniqueEmail();
        String cookie = refreshCookie(post("/auth/signup",
                Map.of("name", "A", "email", email, "password", "correct-horse"), null));
        var logout = post("/auth/logout", null, cookie);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(logout.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        assertThat(post("/auth/refresh", null, cookie).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void signupPublishesUserRegistered() {
        String email = uniqueEmail();
        long id = post("/auth/signup", Map.of("name", "A", "email", email, "password", "correct-horse"), null)
                .getBody().at("/user/id").asLong();
        var types = kafka().readAll("identity-events", Duration.ofSeconds(4)).stream()
                .map(r -> EventEnvelope.parse(r.value(), json))
                .filter(e -> e.userId() == id)
                .map(EventEnvelope::type)
                .toList();
        assertThat(types).containsExactly("user.registered", "user.seen");
    }

    @Test
    void jwksPublishesOnlyThePublicKey() {
        JsonNode jwks = http.getForObject("/.well-known/jwks.json", JsonNode.class);
        JsonNode key = jwks.get("keys").get(0);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.has("d")).isFalse();                       // private exponent never published
    }
}
```

**Verify**

```bash
./gradlew :services:identity-service:test
```

Expected: 9 tests pass (2 unit, 7 integration).

---

## Part B — api-gateway

### Step 2.9 — Module and build file

Add to `settings.gradle.kts`:

```kotlin
include("services:api-gateway")
```

`services/api-gateway/build.gradle.kts`:

```kotlin
plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(libs.wiremock)
    testImplementation(project(":platform:test-support")) {
        // only TestJwt is needed; keep the servlet stack out of this WebFlux app
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
    }
}
```

`services/api-gateway/src/main/java/com/aravindakv/hiretrack/gateway/GatewayApplication.java`:

```java
package com.aravindakv.hiretrack.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

### Step 2.10 — Gateway configuration

`services/api-gateway/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: api-gateway
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  security:
    oauth2:
      resourceserver:
        jwt:
          public-key-location: file:${HIRETRACK_KEYS_DIR:../../deploy/dev-keys}/public.pem

server:
  port: 8080
  shutdown: graceful

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,gateway
  endpoint:
    health:
      probes:
        enabled: true
  metrics:
    tags:
      service: api-gateway
      env: ${HIRETRACK_ENV:local}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: ${TRACING_SAMPLE_RATE:1.0}
  otlp:
    tracing:
      endpoint: ${OTLP_TRACING_ENDPOINT:http://localhost:4318/v1/traces}

logging:
  structured:
    format:
      console: ecs

hiretrack:
  gateway:
    allowed-origins: ${ALLOWED_ORIGINS:http://localhost:5173}
    routes:
      identity: ${IDENTITY_URL:http://localhost:8081}
      resume: ${RESUME_URL:http://localhost:8082}
      match: ${MATCH_URL:http://localhost:8084}
      application: ${APPLICATION_URL:http://localhost:8085}
      feed: ${FEED_URL:http://localhost:8086}
      analytics: ${ANALYTICS_URL:http://localhost:8087}
    rate-limit:
      default-per-second: 20
      default-burst: 40
      expensive-per-second: 1      # /matches and /jobs/refresh
      expensive-burst: 5

---
spring:
  config:
    activate:
      on-profile: prod
  security:
    oauth2:
      resourceserver:
        jwt:
          public-key-location:
          jwk-set-uri: ${JWKS_URI:http://identity-service/.well-known/jwks.json}
```

`config/GatewayProperties.java`:

```java
package com.aravindakv.hiretrack.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("hiretrack.gateway")
public record GatewayProperties(List<String> allowedOrigins, Routes routes, RateLimit rateLimit) {

    public record Routes(String identity, String resume, String match, String application,
                         String feed, String analytics) {}

    public record RateLimit(int defaultPerSecond, int defaultBurst, int expensivePerSecond, int expensiveBurst) {}
}
```

### Step 2.11 — Routes, rate limits and timeouts

`config/RoutesConfig.java`:

```java
package com.aravindakv.hiretrack.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.security.Principal;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.CONNECT_TIMEOUT_ATTR;
import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

/**
 * Browser path /api/v1/<area>/... → service path /<area>/... (stripPrefix(2) removes "api" and "v1").
 * Only these prefixes are routed, so /internal/** on the services is never reachable from outside.
 * Response timeouts follow the timeout chain in Phase 6: each hop waits longer than the one behind it.
 */
@Configuration
public class RoutesConfig {

    @Bean
    @Primary
    RedisRateLimiter defaultRateLimiter(GatewayProperties props) {
        return new RedisRateLimiter(props.rateLimit().defaultPerSecond(), props.rateLimit().defaultBurst(), 1);
    }

    @Bean
    RedisRateLimiter expensiveRateLimiter(GatewayProperties props) {
        return new RedisRateLimiter(props.rateLimit().expensivePerSecond(), props.rateLimit().expensiveBurst(), 1);
    }

    /** Rate-limit by user ID when logged in, by client IP otherwise (signup, login). */
    @Bean
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    var addr = exchange.getRequest().getRemoteAddress();
                    return "ip:" + (addr == null ? "unknown" : addr.getAddress().getHostAddress());
                }));
    }

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties props, KeyResolver keys,
                        RedisRateLimiter defaultRateLimiter, RedisRateLimiter expensiveRateLimiter) {
        GatewayProperties.Routes r = props.routes();
        return builder.routes()
                .route("identity", p -> p.path("/api/v1/auth/**")
                        .filters(f -> f.stripPrefix(2)
                                .requestRateLimiter(c -> c.setRateLimiter(defaultRateLimiter).setKeyResolver(keys)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 5_000).metadata(CONNECT_TIMEOUT_ATTR, 2_000)
                        .uri(r.identity()))
                .route("resume", p -> p.path("/api/v1/resumes/**")
                        .filters(f -> f.stripPrefix(2)
                                .requestRateLimiter(c -> c.setRateLimiter(defaultRateLimiter).setKeyResolver(keys)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 10_000).metadata(CONNECT_TIMEOUT_ATTR, 2_000)
                        .uri(r.resume()))
                .route("match-run", p -> p.path("/api/v1/matches").and().method("POST")
                        .filters(f -> f.stripPrefix(2)
                                .requestRateLimiter(c -> c.setRateLimiter(expensiveRateLimiter).setKeyResolver(keys)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 17_000).metadata(CONNECT_TIMEOUT_ATTR, 2_000)
                        .uri(r.match()))
                .route("match", p -> p.path("/api/v1/matches/**", "/api/v1/matches")
                        .filters(f -> f.stripPrefix(2)
                                .requestRateLimiter(c -> c.setRateLimiter(defaultRateLimiter).setKeyResolver(keys)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 5_000).metadata(CONNECT_TIMEOUT_ATTR, 2_000)
                        .uri(r.match()))
                .route("application", p -> p.path("/api/v1/applications/**", "/api/v1/applications",
                                "/api/v1/interviews/**")
                        .filters(f -> f.stripPrefix(2)
                                .requestRateLimiter(c -> c.setRateLimiter(defaultRateLimiter).setKeyResolver(keys)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 5_000).metadata(CONNECT_TIMEOUT_ATTR, 2_000)
                        .uri(r.application()))
                .route("feed-refresh", p -> p.path("/api/v1/jobs/refresh")
                        .filters(f -> f.stripPrefix(2)
                                .requestRateLimiter(c -> c.setRateLimiter(expensiveRateLimiter).setKeyResolver(keys)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 5_000).metadata(CONNECT_TIMEOUT_ATTR, 2_000)
                        .uri(r.feed()))
                .route("feed", p -> p.path("/api/v1/jobs/**", "/api/v1/jobs", "/api/v1/search-profile")
                        .filters(f -> f.stripPrefix(2)
                                .requestRateLimiter(c -> c.setRateLimiter(defaultRateLimiter).setKeyResolver(keys)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 8_000).metadata(CONNECT_TIMEOUT_ATTR, 2_000)
                        .uri(r.feed()))
                .route("analytics", p -> p.path("/api/v1/dashboard")
                        .filters(f -> f.stripPrefix(2)
                                .requestRateLimiter(c -> c.setRateLimiter(defaultRateLimiter).setKeyResolver(keys)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 5_000).metadata(CONNECT_TIMEOUT_ATTR, 2_000)
                        .uri(r.analytics()))
                .build();
    }
}
```

Route order matters: `match-run` and `feed-refresh` are declared before the broader routes, so the stricter limiter applies to them.

### Step 2.12 — Security, CORS and request IDs

`config/SecurityConfig.java`:

```java
package com.aravindakv.hiretrack.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, CorsConfigurationSource cors) {
        ServerAuthenticationEntryPoint entryPoint = (exchange, ex) -> {
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"Log in to continue. Your session may have expired.\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        };
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(c -> c.configurationSource(cors))
                .authorizeExchange(a -> a
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/api/v1/auth/signup", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/actuator/**").denyAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match", "X-Request-Id"));
        cfg.setExposedHeaders(List.of("ETag", "Retry-After", "X-Request-Id", "Idempotent-Replayed", "Location"));
        cfg.setAllowCredentials(true);                 // the refresh cookie
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
```

`filter/RequestIdFilter.java`:

```java
package com.aravindakv.hiretrack.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/** Every request gets an X-Request-Id; services log it and echo it back. */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    private static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.set(HEADER, id);
                    h.remove("X-Internal-Token");      // never forward a client-supplied internal token
                }))
                .build();
        mutated.getResponse().getHeaders().set(HEADER, id);
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

**Verify** (gateway + identity together)

```bash
tools/run-service.sh identity-service      # terminal 1
tools/run-service.sh api-gateway           # terminal 2
```

```bash
# 1. Login through the gateway
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"correct-horse"}' | jq -r .accessToken)
curl -si localhost:8080/api/v1/auth/me -H "Authorization: Bearer $TOKEN" | sed -n '1p;/X-Request-Id/p'
# 2. No token on a protected route
curl -s localhost:8080/api/v1/applications | jq
# 3. An internal path is not routed
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/internal/users/1/active-resume -H "Authorization: Bearer $TOKEN"
```

Expected:

1. `HTTP/1.1 200`, plus an `X-Request-Id` header
2. `{"error":{"code":"UNAUTHENTICATED",...}}`
3. `404`

```bash
# 4. Rate limit on an expensive route: burst of 5, then 429
for i in $(seq 1 8); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST localhost:8080/api/v1/jobs/refresh -H "Authorization: Bearer $TOKEN"
done; echo
```

Expected: the first five return `5xx` (job-feed-service isn't running yet, so the gateway can't reach it), and the last ones return `429`. The limiter runs before routing.

### Step 2.13 — Gateway tests (backends replaced by WireMock)

`src/test/resources/application-test.yml`:

```yaml
management:
  tracing:
    enabled: false
logging:
  structured:
    format:
      console:
```

`src/test/java/com/aravindakv/hiretrack/gateway/GatewayIT.java`:

```java
package com.aravindakv.hiretrack.gateway;

import com.aravindakv.hiretrack.testsupport.TestJwt;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Testcontainers
@Import(GatewayIT.TestJwtConfig.class)
class GatewayIT {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @RegisterExtension
    static WireMockExtension backend = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        for (String svc : new String[]{"identity", "resume", "match", "application", "feed", "analytics"}) {
            registry.add("hiretrack.gateway.routes." + svc, backend::baseUrl);
        }
        registry.add("hiretrack.gateway.rate-limit.expensive-per-second", () -> 1);
        registry.add("hiretrack.gateway.rate-limit.expensive-burst", () -> 2);
    }

    @TestConfiguration
    static class TestJwtConfig {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return NimbusReactiveJwtDecoder.withPublicKey(TestJwt.publicKey()).build();
        }
    }

    @Autowired WebTestClient web;

    @Test
    void protectedRouteWithoutTokenIs401() {
        web.get().uri("/api/v1/applications").exchange()
           .expectStatus().isUnauthorized()
           .expectBody().jsonPath("$.error.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void validTokenIsRoutedWithPrefixStrippedAndRequestId() {
        backend.stubFor(get("/applications").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));

        web.get().uri("/api/v1/applications").headers(h -> {
                    h.setBearerAuth(TestJwt.token(5));
                    h.set("X-Internal-Token", "spoofed");
                })
           .exchange()
           .expectStatus().isOk()
           .expectHeader().exists("X-Request-Id");

        backend.verify(getRequestedFor(urlEqualTo("/applications"))
                .withHeader("Authorization", matching("Bearer .+"))
                .withHeader("X-Request-Id", matching("[A-Za-z0-9-]{8,64}"))
                .withHeader("X-Internal-Token", absent()));
    }

    @Test
    void publicAuthRoutesNeedNoToken() {
        backend.stubFor(post("/auth/login").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        web.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
    }

    @Test
    void expensiveRouteIsRateLimitedPerUser() {
        backend.stubFor(post("/jobs/refresh").willReturn(aResponse().withStatus(202)));
        String token = TestJwt.token(77);
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            int status = web.post().uri("/api/v1/jobs/refresh").headers(h -> h.setBearerAuth(token))
                    .exchange().returnResult(Void.class).getStatus().value();
            if (status == 429) limited++;
        }
        org.assertj.core.api.Assertions.assertThat(limited).isGreaterThanOrEqualTo(3);

        // another user is unaffected
        web.post().uri("/api/v1/jobs/refresh").headers(h -> h.setBearerAuth(TestJwt.token(78)))
           .exchange().expectStatus().isAccepted();
    }

    @Test
    void unknownPrefixIsNotRouted() {
        web.get().uri("/api/v1/internal/users/1/active-resume").headers(h -> h.setBearerAuth(TestJwt.token(1)))
           .exchange().expectStatus().isNotFound();
    }
}
```

**Verify**

```bash
./gradlew :services:api-gateway:test
```

Expected: 5 tests pass.

---

## Phase demo

With identity-service and the gateway running (Step 2.12), save this as `tools/demo-phase2.sh` and run it:

```bash
#!/usr/bin/env bash
set -euo pipefail
GW=localhost:8080/api/v1
EMAIL="demo$(date +%s)@example.com"
JAR=$(mktemp)

echo "1. signup"
curl -sf -X POST $GW/auth/signup -H 'Content-Type: application/json' -c "$JAR" \
  -d "{\"name\":\"Demo\",\"email\":\"$EMAIL\",\"password\":\"correct-horse\"}" | jq -c '{user}'

echo "2. refresh with the cookie (rotates it)"
TOKEN=$(curl -sf -X POST $GW/auth/refresh -b "$JAR" -c "$JAR" | jq -r .accessToken)

echo "3. me"
curl -sf $GW/auth/me -H "Authorization: Bearer $TOKEN" | jq -c .

echo "4. logout, then refresh must fail"
curl -sf -X POST $GW/auth/logout -b "$JAR" -o /dev/null -w '%{http_code}\n'
curl -s -X POST $GW/auth/refresh -b "$JAR" | jq -c .error
```

Expected output:

```text
1. signup
{"user":{"id":...,"name":"Demo","email":"demo...@example.com"}}
2. refresh with the cookie (rotates it)
3. me
{"id":...,"name":"Demo","email":"demo...@example.com"}
4. logout, then refresh must fail
204
{"code":"REFRESH_TOKEN_MISSING","message":"Log in to continue."}
```

The last line shows `REFRESH_TOKEN_MISSING` because logout cleared the cookie in the jar. To check that the server really revoked the token, re-send the saved cookie value by hand: you get `REFRESH_TOKEN_REVOKED`.

## Done when

- [ ] identity-service: 9 tests pass; signup, login, refresh rotation, reuse detection, logout and rate limiting all work
- [ ] api-gateway: 5 tests pass; no token → 401, internal paths → 404, client `X-Internal-Token` stripped, per-user rate limits
- [ ] `user.registered` and `user.seen` events appear on `identity-events`
- [ ] Phase demo output matches
- [ ] Commit: `git commit -am "Phase 2: identity-service and api-gateway"`
