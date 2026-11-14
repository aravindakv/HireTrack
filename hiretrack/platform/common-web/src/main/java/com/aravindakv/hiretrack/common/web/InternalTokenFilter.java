package com.aravindakv.hiretrack.common.web;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requests to /internal/** must carry X-Internal-Token. The gateway never routes these paths,
 * and Kubernetes NetworkPolicies (Phase 13, on AWS) limit who can reach them; this is the third layer.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expected;
    private final ObjectMapper json;

    public InternalTokenFilter(String expectedToken, ObjectMapper json) {
        this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(),
                    ErrorResponse.of("INTERNAL_AUTH_REQUIRED", "This endpoint is for internal services only."));
            return;
        }
        chain.doFilter(request, response);
    }
}