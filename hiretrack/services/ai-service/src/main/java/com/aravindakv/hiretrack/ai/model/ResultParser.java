package com.aravindakv.hiretrack.ai.model;

import com.aravindakv.hiretrack.ai.provider.AiRejectedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ResultParser {

    private ResultParser() {}

    public static MatchResult match(String jsonText, ObjectMapper json) {
        JsonNode n = read(jsonText, json);
        int score = intIn(n, "fit_score", 0, 100);
        List<String> bullets = strings(n.get("suggested_bullets"), 3);
        if (bullets.size() != 3) {
            throw new AiRejectedException("AI_INVALID_OUTPUT", "expected exactly 3 bullets, got " + bullets.size());
        }
        return new MatchResult(score, strings(n.get("missing_skills"), 10), bullets,
                text(n, "company"), text(n, "role"));
    }

    /** Scores for posting IDs we didn't send are dropped (spec 6.2). */
    public static List<FeedScore> feed(String jsonText, Set<String> sentPostingIds, ObjectMapper json) {
        JsonNode scores = read(jsonText, json).get("scores");
        if (scores == null || !scores.isArray()) {
            throw new AiRejectedException("AI_INVALID_OUTPUT", "scores array missing");
        }
        List<FeedScore> out = new ArrayList<>();
        for (JsonNode s : scores) {
            String id = text(s, "posting_id");
            if (!sentPostingIds.contains(id)) continue;
            int score = s.path("fit_score").asInt(-1);
            if (score < 0 || score > 100) continue;
            out.add(new FeedScore(id, score, strings(s.get("missing_skills"), 5), truncate(text(s, "reason"), 300)));
        }
        return out;
    }

    private static JsonNode read(String text, ObjectMapper json) {
        try {
            JsonNode n = json.readTree(text);
            if (n == null || !n.isObject()) throw new AiRejectedException("AI_INVALID_OUTPUT", "not a JSON object");
            return n;
        } catch (tools.jackson.core.JacksonException e) {
            throw new AiRejectedException("AI_INVALID_OUTPUT", "not valid JSON");
        }
    }

    private static int intIn(JsonNode n, String field, int min, int max) {
        JsonNode v = n.get(field);
        if (v == null || !v.isInt() || v.asInt() < min || v.asInt() > max) {
            throw new AiRejectedException("AI_INVALID_OUTPUT", field + " out of range");
        }
        return v.asInt();
    }

    private static List<String> strings(JsonNode arr, int max) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode v : arr) {
                String s = v.asString("").trim();
                if (!s.isEmpty() && out.size() < max) out.add(truncate(s, 400));
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).asString("").trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}