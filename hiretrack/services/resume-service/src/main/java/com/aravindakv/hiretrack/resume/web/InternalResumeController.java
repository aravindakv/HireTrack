package com.aravindakv.hiretrack.resume.web;

import com.aravindakv.hiretrack.resume.domain.Resume;
import com.aravindakv.hiretrack.resume.service.ResumeService;
import com.aravindakv.hiretrack.resume.web.ResumeDtos.ActiveResume;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** For match-service and job-feed-service. Guarded by X-Internal-Token (common-web). */
@RestController
public class InternalResumeController {

    private final ResumeService service;

    public InternalResumeController(ResumeService service) {
        this.service = service;
    }

    @GetMapping("/internal/users/{userId}/active-resume")
    ActiveResume active(@PathVariable long userId) {
        Resume r = service.active(userId);
        return new ActiveResume(r.getId(), r.getContent());
    }
}