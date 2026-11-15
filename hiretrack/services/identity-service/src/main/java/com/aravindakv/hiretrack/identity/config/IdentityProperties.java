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