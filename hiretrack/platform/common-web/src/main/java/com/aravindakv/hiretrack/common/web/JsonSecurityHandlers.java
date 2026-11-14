package com.aravindakv.hiretrack.common.web;

import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes 401/403 in the standard error format instead of Spring's default HTML/empty body. */
final class JsonSecurityHandlers {

    private JsonSecurityHandlers() {}

    static AuthenticationEntryPoint entryPoint(ObjectMapper json) {
        return (request, response, ex) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(),
                    ErrorResponse.of("UNAUTHENTICATED", "Log in to continue. Your session may have expired."));
        };
    }

    static AccessDeniedHandler accessDenied(ObjectMapper json) {
        return (request, response, ex) -> {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(),
                    ErrorResponse.of("FORBIDDEN", "You can't do that."));
        };
    }
}