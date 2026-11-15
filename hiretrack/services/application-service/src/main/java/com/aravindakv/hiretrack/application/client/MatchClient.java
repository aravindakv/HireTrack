package com.aravindakv.hiretrack.application.client;

import com.aravindakv.hiretrack.common.web.InternalRestClients;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class MatchClient {

    public record MatchRef(long id, String company, String role) {}

    private final RestClient rest;

    public MatchClient(RestClient.Builder builder, ObjectMapper json,
                       @Value("${hiretrack.clients.match-url}") String baseUrl,
                       @Value("${hiretrack.internal.token}") String token) {
        this.rest = InternalRestClients.create(builder, baseUrl, token, Duration.ofSeconds(3), json);
    }

    /** Throws ApiException 404 when the match doesn't exist or belongs to someone else. */
    public MatchRef get(long userId, long matchId) {
        return rest.get().uri("/internal/users/{userId}/matches/{matchId}", userId, matchId)
                .retrieve().body(MatchRef.class);
    }
}