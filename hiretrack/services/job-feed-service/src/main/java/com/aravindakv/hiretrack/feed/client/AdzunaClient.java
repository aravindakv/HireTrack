package com.aravindakv.hiretrack.feed.client;

import com.aravindakv.hiretrack.feed.config.FeedProperties;
import tools.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Adzuna job search. Only search results are used; posting pages are never fetched. */
@Component
public class AdzunaClient {

    public record AdzunaPosting(String externalId, String title, String company, String location, String url,
                                String snippet, Integer salaryMin, Integer salaryMax, Instant postedAt) {}

    private final RestClient rest;
    private final FeedProperties.Adzuna cfg;
    private final MeterRegistry meters;

    public AdzunaClient(RestClient.Builder builder, FeedProperties props, MeterRegistry meters) {
        this.cfg = props.adzuna();
        this.meters = meters;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(cfg.timeout());
        this.rest = builder.clone().baseUrl(cfg.baseUrl()).requestFactory(factory).build();
    }

    /** Retries twice with backoff; throws after that so the run records the error. */
    public List<AdzunaPosting> search(String country, String what, String where, Integer distanceKm,
                                      int maxDaysOld, int resultsPerPage) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long start = System.nanoTime();
            try {
                JsonNode body = rest.get().uri(u -> {
                    var b = u.path("/v1/api/jobs/{country}/search/1")
                            .queryParam("app_id", cfg.appId())
                            .queryParam("app_key", cfg.appKey())
                            .queryParam("what", what)
                            .queryParam("max_days_old", maxDaysOld)
                            .queryParam("results_per_page", resultsPerPage)
                            .queryParam("sort_by", "date");
                    if (where != null) b.queryParam("where", where);
                    if (distanceKm != null) b.queryParam("distance", distanceKm);
                    return b.build(country);
                }).retrieve().body(JsonNode.class);
                record("ok", start);
                return parse(body);
            } catch (RuntimeException e) {
                record("error", start);
                last = e;
                sleep(500L * (attempt + 1));
            }
        }
        throw new IllegalStateException("Adzuna unavailable: " + last.getMessage(), last);
    }

    private static List<AdzunaPosting> parse(JsonNode body) {
        List<AdzunaPosting> out = new ArrayList<>();
        if (body == null) return out;
        for (JsonNode r : body.path("results")) {
            String id = r.path("id").asString(null);
            String url = r.path("redirect_url").asString(null);
            if (id == null || url == null) continue;
            out.add(new AdzunaPosting(id, r.path("title").asString("").trim(),
                    r.path("company").path("display_name").asString(null),
                    r.path("location").path("display_name").asString(null), url,
                    r.path("description").asString("").trim(),
                    r.hasNonNull("salary_min") ? r.get("salary_min").asInt() : null,
                    r.hasNonNull("salary_max") ? r.get("salary_max").asInt() : null,
                    r.hasNonNull("created") ? Instant.parse(r.get("created").asString()) : null));
        }
        return out;
    }

    private void record(String outcome, long startNanos) {
        meters.counter("hiretrack.adzuna.requests", "outcome", outcome).increment();
        meters.timer("hiretrack.adzuna.latency").record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}