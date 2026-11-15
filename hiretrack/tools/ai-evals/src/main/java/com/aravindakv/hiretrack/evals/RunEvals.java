package com.aravindakv.hiretrack.evals;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs every case against ai-service and checks the release gate:
 *   schema-valid 100%, score within the expected band >= 85%, company/role correct >= 90%,
 *   must-mention skills found >= 80%. Exit code 1 when a gate fails (CI blocks the release).
 * Usage: ./gradlew :tools:ai-evals:run --args="ai-evals/cases.jsonl http://localhost:8083 dev-internal-token"
 */
public class RunEvals {

    public static void main(String[] args) throws Exception {
        Path cases = Path.of(args.length > 0 ? args[0] : "ai-evals/cases.jsonl");
        String base = args.length > 1 ? args[1] : "http://localhost:8083";
        String token = args.length > 2 ? args[2] : "dev-internal-token";
        ObjectMapper json = JsonMapper.builder().build();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        int total = 0, valid = 0, inBand = 0, entityOk = 0, mentionOk = 0;
        long totalMillis = 0;
        List<String> failures = new ArrayList<>();
        for (String line : Files.readAllLines(cases)) {
            if (line.isBlank()) continue;
            JsonNode c = json.readTree(line);
            total++;
            String body = json.writeValueAsString(Map.of("userId", 999_999, "requestId", "eval-" + UUID.randomUUID(),
                    "resumeText", c.get("resume").asString(), "jobDescription", c.get("job").asString()));
            long start = System.currentTimeMillis();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(base + "/internal/match"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .header("X-Internal-Token", token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            totalMillis += System.currentTimeMillis() - start;
            if (res.statusCode() != 200) { failures.add(c.get("id").asString() + ": HTTP " + res.statusCode()); continue; }
            JsonNode r = json.readTree(res.body());
            valid++;
            int score = r.get("fitScore").asInt();
            if (score >= c.get("scoreMin").asInt() && score <= c.get("scoreMax").asInt()) inBand++;
            else failures.add(c.get("id").asString() + ": score " + score + " outside " + c.get("scoreMin") + "-" + c.get("scoreMax"));
            if (r.get("company").asString().equalsIgnoreCase(c.get("company").asString())
                    && r.get("role").asString().equalsIgnoreCase(c.get("role").asString())) entityOk++;
            else failures.add(c.get("id").asString() + ": company/role " + r.get("company") + "/" + r.get("role"));
            String skills = r.get("missingSkills").toString().toLowerCase();
            boolean allFound = true;
            for (JsonNode m : c.get("mustMention")) allFound &= skills.contains(m.asString().toLowerCase());
            if (allFound) mentionOk++;
        }
        double schema = pct(valid, total), band = pct(inBand, total), entity = pct(entityOk, total), mention = pct(mentionOk, total);
        System.out.printf("cases=%d schema-valid=%.1f%% in-band=%.1f%% company/role=%.1f%% must-mention=%.1f%% avg-latency=%dms%n",
                total, schema, band, entity, mention, total == 0 ? 0 : totalMillis / total);
        failures.forEach(f -> System.out.println("  FAIL " + f));
        boolean pass = schema == 100.0 && band >= 85 && entity >= 90 && mention >= 80;
        System.out.println(pass ? "GATE PASSED" : "GATE FAILED");
        System.exit(pass ? 0 : 1);
    }

    private static double pct(int n, int total) {
        return total == 0 ? 0 : 100.0 * n / total;
    }
}