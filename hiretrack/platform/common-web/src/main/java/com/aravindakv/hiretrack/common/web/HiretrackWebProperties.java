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