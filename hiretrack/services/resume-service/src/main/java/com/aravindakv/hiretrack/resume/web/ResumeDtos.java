package com.aravindakv.hiretrack.resume.web;

import com.aravindakv.hiretrack.resume.domain.Resume;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ResumeDtos {

    private ResumeDtos() {}

    public record PasteRequest(@NotBlank @Size(max = 20000) String text) {}

    public record ResumeSummary(long id, String source, boolean active, int characters, String preview, Instant createdAt) {
        static ResumeSummary of(Resume r) {
            String preview = r.getContent().length() <= 200 ? r.getContent() : r.getContent().substring(0, 200) + "…";
            return new ResumeSummary(r.getId(), r.getSource().name(), r.isActive(), r.getContent().length(),
                    preview, r.getCreatedAt());
        }
    }

    public record ResumeDetail(long id, String source, boolean active, String content, Instant createdAt) {
        static ResumeDetail of(Resume r) {
            return new ResumeDetail(r.getId(), r.getSource().name(), r.isActive(), r.getContent(), r.getCreatedAt());
        }
    }

    public record ActiveResume(long resumeId, String content) {}
}