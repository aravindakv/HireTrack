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
import tools.jackson.databind.ObjectMapper;
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