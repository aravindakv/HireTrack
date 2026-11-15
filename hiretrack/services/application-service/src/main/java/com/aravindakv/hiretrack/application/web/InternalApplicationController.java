package com.aravindakv.hiretrack.application.web;

import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.service.ApplicationService;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.ApplicationResponse;
import com.aravindakv.hiretrack.application.web.ApplicationDtos.CreateApplication;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalApplicationController {

    private final ApplicationService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public InternalApplicationController(ApplicationService service, IdempotencyService idempotency, ObjectMapper json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/internal/users/{userId}/applications")
    ResponseEntity<ApplicationResponse> create(@PathVariable long userId,
                                               @RequestHeader(IdempotencyService.HEADER) String key,
                                               @Valid @RequestBody CreateApplication body) {
        if (body.source() == null || body.source().type() != SourceType.FEED) {
            throw ApiException.badRequest("SOURCE_REQUIRED", "Internal creation needs a FEED source.");
        }
        var req = IdempotentRequest.of("create-application-internal", userId, key, "POST",
                "/internal/applications", body, json);
        return idempotency.execute(req, ApplicationResponse.class, () -> {
            var created = service.create(userId, new ApplicationService.NewApplication(body.company(), body.role(),
                    body.jobUrl(), body.notes(), SourceType.FEED, body.source().id()), false);
            return ResponseEntity.status(created.isNew() ? 201 : 200).body(ApplicationResponse.of(created.application()));
        });
    }
}