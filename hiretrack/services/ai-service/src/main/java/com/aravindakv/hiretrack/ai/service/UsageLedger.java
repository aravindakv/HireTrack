package com.aravindakv.hiretrack.ai.service;

import com.aravindakv.hiretrack.ai.model.TokenUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class UsageLedger {

    private final JdbcTemplate jdbc;

    public UsageLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent on usageKey: recording the same paid call twice keeps one row. */
    public void record(String usageKey, long userId, String provider, String operation, TokenUsage u, BigDecimal cost) {
        jdbc.update("""
                INSERT INTO ai_usage (usage_key, user_id, provider, operation, model,
                                      input_tokens, cached_input_tokens, output_tokens, cost_usd)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (usage_key) DO NOTHING""",
                usageKey, userId, provider, operation, u.model(),
                (int) u.inputTokens(), (int) u.cachedInputTokens(), (int) u.outputTokens(), cost);
    }
}