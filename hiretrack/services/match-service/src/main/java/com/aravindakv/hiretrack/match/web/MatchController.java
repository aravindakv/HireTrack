package com.aravindakv.hiretrack.match.web;

import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.match.service.MatchService;
import com.aravindakv.hiretrack.match.web.MatchDtos.MatchRef;
import com.aravindakv.hiretrack.match.web.MatchDtos.MatchResponse;
import com.aravindakv.hiretrack.match.web.MatchDtos.RunMatch;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MatchController {

    private final MatchService service;

    public MatchController(MatchService service) {
        this.service = service;
    }

    @PostMapping("/matches")
    ResponseEntity<MatchResponse> run(@CurrentUser long userId, @RequestHeader(IdempotencyService.HEADER) String key,
                                      @Valid @RequestBody RunMatch body) {
        return service.run(userId, key, body.jobDescription());
    }

    @GetMapping("/matches")
    List<MatchResponse> list(@CurrentUser long userId) {
        return service.list(userId).stream().map(MatchResponse::of).toList();
    }

    @GetMapping("/matches/{id}")
    MatchResponse get(@CurrentUser long userId, @PathVariable long id) {
        return MatchResponse.of(service.get(userId, id));
    }

    /** For application-service: confirms a match exists and belongs to the user. */
    @GetMapping("/internal/users/{userId}/matches/{id}")
    MatchRef internal(@PathVariable long userId, @PathVariable long id) {
        var m = service.get(userId, id);
        return new MatchRef(m.getId(), m.getCompany(), m.getRole());
    }
}