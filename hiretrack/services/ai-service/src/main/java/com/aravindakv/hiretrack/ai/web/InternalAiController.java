package com.aravindakv.hiretrack.ai.web;

import com.aravindakv.hiretrack.ai.service.FeedBatchService;
import com.aravindakv.hiretrack.ai.service.MatchService;
import com.aravindakv.hiretrack.ai.web.AiDtos.FeedBatchCreated;
import com.aravindakv.hiretrack.ai.web.AiDtos.FeedBatchRequest;
import com.aravindakv.hiretrack.ai.web.AiDtos.MatchRequest;
import com.aravindakv.hiretrack.ai.web.AiDtos.MatchResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class InternalAiController {

    private final MatchService matches;
    private final FeedBatchService batches;

    public InternalAiController(MatchService matches, FeedBatchService batches) {
        this.matches = matches;
        this.batches = batches;
    }

    @PostMapping("/internal/match")
    MatchResponse match(@Valid @RequestBody MatchRequest req) {
        var o = matches.match(req.userId(), req.requestId(), req.resumeText(), req.jobDescription());
        var r = o.result();
        return new MatchResponse(r.fitScore(), r.missingSkills(), r.suggestedBullets(), r.company(), r.role(),
                o.provider(), o.model());
    }

    @PostMapping("/internal/feed-batches")
    FeedBatchCreated submit(@Valid @RequestBody FeedBatchRequest req) {
        return new FeedBatchCreated(batches.submit(req.submissionId(), req.items()).toString());
    }

    @GetMapping("/internal/feed-batches/{batchId}")
    FeedBatchService.BatchView view(@PathVariable UUID batchId) {
        return batches.view(batchId);
    }
}