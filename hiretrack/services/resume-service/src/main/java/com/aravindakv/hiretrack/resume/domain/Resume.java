package com.aravindakv.hiretrack.resume.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Immutable after creation, except for the active flag. */
@Entity
@Table(name = "resumes")
public class Resume {

    public enum Source { PDF, PASTE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    @Column(name = "pdf_object_key", updatable = false)
    private String pdfObjectKey;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Resume() {}

    public Resume(Long userId, String content, Source source, String pdfObjectKey) {
        this.userId = userId;
        this.content = content;
        this.source = source;
        this.pdfObjectKey = pdfObjectKey;
        this.active = true;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getContent() { return content; }
    public Source getSource() { return source; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}