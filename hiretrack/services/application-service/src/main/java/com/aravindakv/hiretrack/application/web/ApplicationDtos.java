package com.aravindakv.hiretrack.application.web;

import com.aravindakv.hiretrack.application.domain.Interview;
import com.aravindakv.hiretrack.application.domain.JobApplication;
import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.domain.Stage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class ApplicationDtos {

    private ApplicationDtos() {}

    public record SourceRef(@NotNull SourceType type, @NotBlank @Size(max = 64) String id) {}

    public record CreateApplication(
            @NotBlank @Size(max = 200) String company,
            @NotBlank @Size(max = 200) String role,
            @Size(max = 2000) @Pattern(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String jobUrl,
            @Size(max = 5000) String notes,
            @Valid SourceRef source) {}

    /** All fields optional. An empty string clears jobUrl or notes. */
    public record UpdateApplication(
            @Size(min = 1, max = 200) String company,
            @Size(min = 1, max = 200) String role,
            @Size(max = 2000) @Pattern(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String jobUrl,
            @Size(max = 5000) String notes,
            Stage stage,
            LocalDate appliedDate) {}

    public record ApplicationResponse(long id, String company, String role, String stage, String jobUrl,
                                      LocalDate appliedDate, Instant respondedAt, String notes,
                                      String sourceType, String sourceId, long version,
                                      Instant createdAt, Instant updatedAt) {
        public static ApplicationResponse of(JobApplication a) {
            return new ApplicationResponse(a.getId(), a.getCompany(), a.getRole(), a.getStage().name(), a.getJobUrl(),
                    a.getAppliedDate(), a.getRespondedAt(), a.getNotes(), a.getSourceType().name(), a.getSourceId(),
                    a.getVersion(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    public record CreateInterview(@NotNull Instant scheduledAt, @Size(max = 100) String round, @Size(max = 5000) String notes) {}

    public record UpdateInterview(Instant scheduledAt, @Size(max = 100) String round, @Size(max = 5000) String notes) {}

    public record InterviewResponse(long id, long applicationId, Instant scheduledAt, String round, String notes) {
        public static InterviewResponse of(Interview i) {
            return new InterviewResponse(i.getId(), i.getApplicationId(), i.getScheduledAt(), i.getRound(), i.getNotes());
        }
    }
}