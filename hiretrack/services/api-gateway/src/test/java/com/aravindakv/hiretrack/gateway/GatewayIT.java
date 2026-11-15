package com.aravindakv.hiretrack.gateway;

import com.aravindakv.hiretrack.testsupport.TestJwt;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
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