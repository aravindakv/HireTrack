package com.aravindakv.hiretrack.application.web;

import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.domain.Stage;
import com.aravindakv.hiretrack.application.service.ApplicationService;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.ApplicationResponse;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.CreateApplication;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.CreateInterview;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.InterviewResponse;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.UpdateApplication;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.UpdateInterview;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
public class ApplicationController {

    private final ApplicationService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public ApplicationController(ApplicationService service, IdempotencyService idempotency, ObjectMapper json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping("/applications")
    List<ApplicationResponse> list(@CurrentUser long userId, @RequestParam(required = false) Stage stage) {
        return service.list(userId, stage).stream().map(ApplicationResponse::of).toList();
    }

    @GetMapping("/applications/{id}")
    ResponseEntity<ApplicationResponse> get(@CurrentUser long userId, @PathVariable long id) {
        var a = service.get(userId, id);
        return ResponseEntity.ok().eTag(ETags.of(a.getVersion())).body(ApplicationResponse.of(a));
    }

    @PostMapping("/applications")
    ResponseEntity<ApplicationResponse> create(@CurrentUser long userId,
                                               @RequestHeader(IdempotencyService.HEADER) String key,
                                               @Valid @RequestBody CreateApplication body) {
        var req = IdempotentRequest.of("create-application", userId, key, "POST", "/applications", body, json);
        return idempotency.execute(req, ApplicationResponse.class, () -> {
            SourceType type = body.source() == null ? SourceType.MANUAL : body.source().type();
            String sourceId = body.source() == null ? null : body.source().id();
            var created = service.create(userId, new ApplicationService.NewApplication(
                    body.company(), body.role(), body.jobUrl(), body.notes(), type, sourceId), true);
            var a = created.application();
            return ResponseEntity.status(created.isNew() ? 201 : 200)
                    .location(URI.create("/applications/" + a.getId()))
                    .eTag(ETags.of(a.getVersion()))
                    .body(ApplicationResponse.of(a));
        });
    }

    @PatchMapping("/applications/{id}")
    ResponseEntity<ApplicationResponse> update(@CurrentUser long userId, @PathVariable long id,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @Valid @RequestBody UpdateApplication body) {
        var a = service.update(userId, id, ETags.parse(ifMatch), new ApplicationService.Changes(
                body.company(), body.role(), body.jobUrl(), body.notes(), body.stage(), body.appliedDate()));
        return ResponseEntity.ok().eTag(ETags.of(a.getVersion())).body(ApplicationResponse.of(a));
    }

    @DeleteMapping("/applications/{id}")
    ResponseEntity<Void> delete(@CurrentUser long userId, @PathVariable long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/applications/{id}/interviews")
    List<InterviewResponse> interviews(@CurrentUser long userId, @PathVariable long id) {
        return service.interviews(userId, id).stream().map(InterviewResponse::of).toList();
    }

    @PostMapping("/applications/{id}/interviews")
    ResponseEntity<InterviewResponse> addInterview(@CurrentUser long userId, @PathVariable long id,
                                                   @RequestHeader(IdempotencyService.HEADER) String key,
                                                   @Valid @RequestBody CreateInterview body) {
        var req = IdempotentRequest.of("create-interview", userId, key, "POST",
                "/applications/" + id + "/interviews", body, json);
        return idempotency.execute(req, InterviewResponse.class, () -> ResponseEntity.status(201).body(
                InterviewResponse.of(service.addInterview(userId, id, body.scheduledAt(), body.round(), body.notes()))));
    }

    @PatchMapping("/interviews/{id}")
    InterviewResponse editInterview(@CurrentUser long userId, @PathVariable long id,
                                    @Valid @RequestBody UpdateInterview body) {
        return InterviewResponse.of(service.editInterview(userId, id, body.scheduledAt(), body.round(), body.notes()));
    }

    @DeleteMapping("/interviews/{id}")
    ResponseEntity<Void> deleteInterview(@CurrentUser long userId, @PathVariable long id) {
        service.deleteInterview(userId, id);
        return ResponseEntity.noContent().build();
    }
}