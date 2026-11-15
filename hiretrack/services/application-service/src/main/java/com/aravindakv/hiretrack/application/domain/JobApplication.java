package com.aravindakv.hiretrack.application.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "applications")
public class JobApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String company;

    @Column(nullable = false)
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Stage stage = Stage.WISHLIST;

    @Column(name = "job_url")
    private String jobUrl;

    @Column(name = "applied_date")
    private LocalDate appliedDate;

    @Column(name = "responded_at")
    private Instant respondedAt;

    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private SourceType sourceType = SourceType.MANUAL;

    @Column(name = "source_id", updatable = false)
    private String sourceId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected JobApplication() {}

    public JobApplication(Long userId, String company, String role, String jobUrl, String notes,
                          SourceType sourceType, String sourceId) {
        this.userId = userId;
        this.company = company;
        this.role = role;
        this.jobUrl = jobUrl;
        this.notes = notes;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
    }

    /**
     * Spec 3.4 stage rules. Returns false when nothing changed (moving to the current stage),
     * so repeating the same request is harmless.
     */
    public boolean moveTo(Stage target, LocalDate today, Instant now) {
        if (target == stage) return false;
        if (target == Stage.WISHLIST) {
            appliedDate = null;
            respondedAt = null;
        } else {
            if (target.countsAsSent() && appliedDate == null) {
                appliedDate = today;
            }
            if (target.countsAsReply() && appliedDate != null && respondedAt == null) {
                respondedAt = now;
            }
        }
        stage = target;
        return true;
    }

    /** The user may correct the applied date, but only on an application that was sent. */
    public void correctAppliedDate(LocalDate date) {
        if (appliedDate == null) {
            throw new IllegalStateException("Only a sent application has an applied date");
        }
        appliedDate = date;
    }

    public void edit(String company, String role, String jobUrl, String notes) {
        if (company != null) this.company = company;
        if (role != null) this.role = role;
        if (jobUrl != null) this.jobUrl = jobUrl.isBlank() ? null : jobUrl;
        if (notes != null) this.notes = notes.isBlank() ? null : notes;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getCompany() { return company; }
    public String getRole() { return role; }
    public Stage getStage() { return stage; }
    public String getJobUrl() { return jobUrl; }
    public LocalDate getAppliedDate() { return appliedDate; }
    public Instant getRespondedAt() { return respondedAt; }
    public String getNotes() { return notes; }
    public SourceType getSourceType() { return sourceType; }
    public String getSourceId() { return sourceId; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}