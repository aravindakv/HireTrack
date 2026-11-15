package com.aravindakv.hiretrack.resume.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import com.aravindakv.hiretrack.resume.config.ResumeProperties;
import com.aravindakv.hiretrack.resume.domain.Resume;
import com.aravindakv.hiretrack.resume.persistence.ResumeRepository;
import com.aravindakv.hiretrack.resume.storage.ResumeStorage;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class ResumeService {

    private final ResumeRepository resumes;
    private final ResumeStorage storage;
    private final PdfTextExtractor pdf;
    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final int maxCharacters;

    public ResumeService(ResumeRepository resumes, ResumeStorage storage, PdfTextExtractor pdf, OutboxWriter outbox,
                         ObjectMapper json, TransactionTemplate tx, ResumeProperties props) {
        this.resumes = resumes;
        this.storage = storage;
        this.pdf = pdf;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
        this.maxCharacters = props.resume().maxCharacters();
    }

    public Resume createFromText(long userId, String text) {
        String content = PdfTextExtractor.normalise(text);
        checkLength(content);
        return withActivationRetry(() -> insertActive(userId, content, Resume.Source.PASTE, null));
    }

    public Resume createFromPdf(long userId, byte[] bytes) {
        String content = pdf.extract(bytes);
        checkLength(content);
        String key = storage.storePdf(userId, bytes);
        try {
            return withActivationRetry(() -> insertActive(userId, content, Resume.Source.PDF, key));
        } catch (RuntimeException e) {
            storage.deleteQuietly(key);       // don't leave an orphaned file when the DB write fails
            throw e;
        }
    }

    public Resume activate(long userId, long resumeId) {
        return withActivationRetry(() -> {
            Resume resume = resumes.findByIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> ApiException.notFound("Resume"));
            if (resume.isActive()) return resume;              // idempotent
            resumes.deactivateAll(userId);
            resumes.activate(resumeId, userId);
            publishActivated(userId, resumeId);
            return resumes.findById(resumeId).orElseThrow();
        });
    }

    @Transactional(readOnly = true)
    public List<Resume> list(long userId) {
        return resumes.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Resume get(long userId, long resumeId) {
        return resumes.findByIdAndUserId(resumeId, userId).orElseThrow(() -> ApiException.notFound("Resume"));
    }

    @Transactional(readOnly = true)
    public Resume active(long userId) {
        return resumes.findByUserIdAndActiveTrue(userId)
                .orElseThrow(() -> ApiException.of(404, "NO_ACTIVE_RESUME", "Add a resume first."));
    }

    private Resume insertActive(long userId, String content, Resume.Source source, String key) {
        resumes.deactivateAll(userId);
        Resume saved = resumes.saveAndFlush(new Resume(userId, content, source, key));
        publishActivated(userId, saved.getId());
        return saved;
    }

    private void publishActivated(long userId, long resumeId) {
        outbox.write(Topics.RESUME_EVENTS, EventEnvelope.create(EventTypes.RESUME_ACTIVATED, userId,
                Long.toString(resumeId), 0, Map.of("resumeId", resumeId), json));
    }

    /**
     * Two requests activating at the same moment: both deactivate, both insert "active", and the
     * partial unique index rejects one. The loser retries once in a fresh transaction and wins.
     */
    private Resume withActivationRetry(Supplier<Resume> work) {
        try {
            return tx.execute(s -> work.get());
        } catch (DataIntegrityViolationException first) {
            return tx.execute(s -> work.get());
        }
    }

    private void checkLength(String content) {
        if (content.isEmpty()) {
            throw ApiException.badRequest("RESUME_EMPTY", "The resume is empty.");
        }
        if (content.length() > maxCharacters) {
            throw ApiException.unprocessable("RESUME_TOO_LONG",
                    "The resume is longer than " + maxCharacters + " characters. Shorten it and try again.");
        }
    }
}