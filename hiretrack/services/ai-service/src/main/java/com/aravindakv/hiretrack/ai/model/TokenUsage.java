package com.aravindakv.hiretrack.ai.model;

public record TokenUsage(String model, long inputTokens, long cachedInputTokens, long outputTokens) {}