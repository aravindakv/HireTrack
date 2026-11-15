package com.aravindakv.hiretrack.ai.model;

import java.util.List;

public record MatchResult(int fitScore, List<String> missingSkills, List<String> suggestedBullets,
                          String company, String role) {}