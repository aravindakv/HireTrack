package com.aravindakv.hiretrack.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("hiretrack.gateway")
public record GatewayProperties(List<String> allowedOrigins, Routes routes, RateLimit rateLimit) {

    public record Routes(String identity, String resume, String match, String application,
                         String feed, String analytics) {}

    public record RateLimit(int defaultPerSecond, int defaultBurst, int expensivePerSecond, int expensiveBurst) {}
}