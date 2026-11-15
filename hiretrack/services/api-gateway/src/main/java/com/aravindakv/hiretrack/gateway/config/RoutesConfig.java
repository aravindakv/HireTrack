package com.aravindakv.hiretrack.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
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
                        RedisRateLimiter defaultRateLimiter,
                        @Qualifier("expensiveRateLimiter") RedisRateLimiter expensiveRateLimiter) {
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