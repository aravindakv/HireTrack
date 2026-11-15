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