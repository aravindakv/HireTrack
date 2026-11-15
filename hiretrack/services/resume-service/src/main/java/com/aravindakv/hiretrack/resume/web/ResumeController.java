package com.aravindakv.hiretrack.resume.web;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import com.aravindakv.hiretrack.resume.service.ResumeService;
import com.aravindakv.hiretrack.resume.web.ResumeDtos.PasteRequest;
import com.aravindakv.hiretrack.resume.web.ResumeDtos.ResumeDetail;
import com.aravindakv.hiretrack.resume.web.ResumeDtos.ResumeSummary;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RequestMapping("/resumes")
@RestController
public class ResumeController {

    private final ResumeService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public ResumeController(ResumeService service, IdempotencyService idempotency, ObjectMapper json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping
    List<ResumeSummary> list(@CurrentUser long userId) {
        return service.list(userId).stream().map(ResumeSummary::of).toList();
    }

    @GetMapping("/{id}")
    ResumeDetail get(@CurrentUser long userId, @PathVariable long id) {
        return ResumeDetail.of(service.get(userId, id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ResumeDetail> paste(@CurrentUser long userId,
                                       @RequestHeader(IdempotencyService.HEADER) String key,
                                       @Valid @RequestBody PasteRequest body) {
        var req = IdempotentRequest.of("create-resume", userId, key, "POST", "/resumes", body, json);
        return idempotency.execute(req, ResumeDetail.class,
                () -> ResponseEntity.status(201).body(ResumeDetail.of(service.createFromText(userId, body.text()))));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ResumeDetail> upload(@CurrentUser long userId,
                                        @RequestHeader(IdempotencyService.HEADER) String key,
                                        @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("FILE_EMPTY", "Choose a PDF file to upload.");
        }
        byte[] bytes = file.getBytes();
        var req = IdempotentRequest.ofBytes("create-resume", userId, key, "POST", "/resumes", bytes);
        return idempotency.execute(req, ResumeDetail.class,
                () -> ResponseEntity.status(201).body(ResumeDetail.of(service.createFromPdf(userId, bytes))));
    }

    @PostMapping("/{id}/activate")
    ResumeDetail activate(@CurrentUser long userId, @PathVariable long id) {
        return ResumeDetail.of(service.activate(userId, id));
    }
}