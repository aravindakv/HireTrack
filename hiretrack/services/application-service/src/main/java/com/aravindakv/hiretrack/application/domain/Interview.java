package com.aravindakv.hiretrack.application.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "interviews")
public class Interview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    private String round;
    private String notes;

    @Version
    private Long version;

    protected Interview() {}

    public Interview(Long applicationId, Instant scheduledAt, String round, String notes) {
        this.applicationId = applicationId;
        this.scheduledAt = scheduledAt;
        this.round = round;
        this.notes = notes;
    }

    public void edit(Instant scheduledAt, String round, String notes) {
        if (scheduledAt != null) this.scheduledAt = scheduledAt;
        if (round != null) this.round = round.isBlank() ? null : round;
        if (notes != null) this.notes = notes.isBlank() ? null : notes;
    }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public Instant getScheduledAt() { return scheduledAt; }
    public String getRound() { return round; }
    public String getNotes() { return notes; }
    public Long getVersion() { return version; }
}