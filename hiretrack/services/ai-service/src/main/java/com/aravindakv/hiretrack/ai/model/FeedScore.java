package com.aravindakv.hiretrack.ai.model;

import java.util.List;

public record FeedScore(String postingId, int fitScore, List<String> missingSkills, String reason) {}