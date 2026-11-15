package com.aravindakv.hiretrack.match.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "matches")
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false) private Long userId;
    @Column(name = "resume_id", nullable = false, updatable = false) private Long resumeId;
    @Column(name = "job_description", nullable = false, updatable = false) private String jobDescription;
    @Column(updatable = false) private String company;
    @Column(updatable = false) private String role;
    @Column(name = "fit_score", nullable = false, updatable = false) private int fitScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_skills", nullable = false, updatable = false)
    private List<String> missingSkills;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggested_bullets", nullable = false, updatable = false)
    private List<String> suggestedBullets;

    @Column(name = "ai_provider", nullable = false, updatable = false) private String aiProvider;
    @Column(name = "ai_model", nullable = false, updatable = false) private String aiModel;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected Match() {}

    public Match(Long userId, Long resumeId, String jobDescription, String company, String role, int fitScore,
                 List<String> missingSkills, List<String> suggestedBullets, String aiProvider, String aiModel) {
        this.userId = userId;
        this.resumeId = resumeId;
        this.jobDescription = jobDescription;
        this.company = company == null || company.isBlank() ? null : company;
        this.role = role == null || role.isBlank() ? null : role;
        this.fitScore = fitScore;
        this.missingSkills = missingSkills;
        this.suggestedBullets = suggestedBullets;
        this.aiProvider = aiProvider;
        this.aiModel = aiModel;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getResumeId() { return resumeId; }
    public String getJobDescription() { return jobDescription; }
    public String getCompany() { return company; }
    public String getRole() { return role; }
    public int getFitScore() { return fitScore; }
    public List<String> getMissingSkills() { return missingSkills; }
    public List<String> getSuggestedBullets() { return suggestedBullets; }
    public Instant getCreatedAt() { return createdAt; }
}