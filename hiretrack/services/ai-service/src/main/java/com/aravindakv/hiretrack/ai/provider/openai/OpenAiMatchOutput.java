package com.aravindakv.hiretrack.ai.provider.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Shape of the structured output for real-time matches (OpenAI class-based structured outputs). */
public class OpenAiMatchOutput {

    @JsonProperty("fit_score")
    @JsonPropertyDescription("0-100; 100 means the resume covers every requirement")
    public int fitScore;

    @JsonProperty("missing_skills")
    @JsonPropertyDescription("Skills the posting asks for that the resume does not show, at most 10")
    public List<String> missingSkills;

    @JsonProperty("suggested_bullets")
    @JsonPropertyDescription("Exactly three resume bullet points tailored to this job")
    public List<String> suggestedBullets;

    @JsonPropertyDescription("Hiring company named in the posting, or empty")
    public String company;

    @JsonPropertyDescription("Role title named in the posting, or empty")
    public String role;
}