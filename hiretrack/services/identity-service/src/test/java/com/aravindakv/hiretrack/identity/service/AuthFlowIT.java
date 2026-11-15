package com.aravindakv.hiretrack.identity;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.testsupport.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

        String token = signup.getBody().get("accessToken").asString();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        var me = http.exchange("/auth/me", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(me.getBody().get("email").asString()).isEqualTo(email.toLowerCase());
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        String email = uniqueEmail();
        post("/auth/signup", Map.of("name", "A", "email", email, "password", "correct-horse"), null);
        var again = post("/auth/signup", Map.of("name", "B", "email", email.toUpperCase(), "password", "correct-horse"), null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().at("/error/code").asString()).isEqualTo("EMAIL_TAKEN");
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
        assertThat(reuse.getBody().at("/error/code").asString()).isEqualTo("REFRESH_TOKEN_REUSED");

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
        assertThat(key.get("kty").asString()).isEqualTo("RSA");
        assertThat(key.has("d")).isFalse();                       // private exponent never published
    }
}