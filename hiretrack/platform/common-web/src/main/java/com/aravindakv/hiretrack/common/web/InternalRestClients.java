package com.aravindakv.hiretrack.common.web;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds a RestClient for calling another service's /internal/** API.
 * - Starts from Spring Boot's RestClient.Builder, so calls are traced and timed.
 * - Sends X-Internal-Token and propagates X-Request-Id.
 * - Turns error responses into ApiException with the downstream status and code,
 *   so a 404 or 429 from the callee reaches the browser unchanged.
 */
public final class InternalRestClients {

    private InternalRestClients() {}

    public static RestClient create(RestClient.Builder builder, String baseUrl, String internalToken,
                                    Duration readTimeout, ObjectMapper json) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);

        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(InternalTokenFilter.HEADER, internalToken)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().set(RequestIdFilter.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw toApiException(response, json);
                })
                .build();
    }

    static ApiException toApiException(ClientHttpResponse response, ObjectMapper json) throws IOException {
        int status = response.getStatusCode().value();
        ErrorResponse body = null;
        try {
            body = json.readValue(response.getBody(), ErrorResponse.class);
        } catch (IOException | JacksonException ignored) {
            // not our error format; fall through
        }
        boolean propagate = status < 500 || status == 502 || status == 503;
        if (body != null && body.error() != null && propagate) {
            String retryAfter = response.getHeaders().getFirst("Retry-After");
            Integer retry = retryAfter == null ? null : Integer.valueOf(retryAfter);
            return new ApiException(status, body.error().code(), body.error().message(), retry);
        }
        return ApiException.upstream("UPSTREAM_ERROR", "A dependent service failed. Try again.");
    }
}