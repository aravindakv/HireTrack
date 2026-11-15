package com.aravindakv.hiretrack.match.service;

import com.aravindakv.hiretrack.common.events.EventEnvelope;
import com.aravindakv.hiretrack.common.events.EventTypes;
import com.aravindakv.hiretrack.common.events.Topics;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.idempotency.IdempotencyService;
import com.aravindakv.hiretrack.idempotency.IdempotentRequest;
import com.aravindakv.hiretrack.match.client.Clients;
import com.aravindakv.hiretrack.match.domain.Match;
import com.aravindakv.hiretrack.match.persistence.MatchRepository;
import com.aravindakv.hiretrack.match.web.MatchDtos.MatchResponse;
import com.aravindakv.hiretrack.outbox.OutboxWriter;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
public class MatchService {

    private final Clients clients;
    private final MatchRepository matches;
    private final OutboxWriter outbox;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public MatchService(Clients clients, MatchRepository matches, OutboxWriter outbox, IdempotencyService idempotency,
                        TransactionTemplate tx, ObjectMapper json) {
        this.clients = clients;
        this.matches = matches;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.tx = tx;
        this.json = json;
    }

    /**
     * 1. claim the Idempotency-Key (platform)
     * 2. fetch the active resume, call ai-service with requestId derived from the key
     * 3. in ONE transaction: save the match, write match.completed, mark the key completed
     * A crash after step 2 is safe: the retry reuses the same requestId, so ai-service returns the
     * stored result instead of paying again.
     */
    public ResponseEntity<MatchResponse> run(long userId, String key, String jobDescription) {
        var req = IdempotentRequest.of("run-match", userId, key, "POST", "/matches",
                Map.of("jobDescription", jobDescription), json);
        return idempotency.execute(req, MatchResponse.class, () -> {
            Clients.ActiveResume resume = clients.activeResume(userId);
            Clients.AiMatch ai = clients.match(userId, "match-" + userId + "-" + key, resume.content(), jobDescription);
            return tx.execute(s -> {
                Match saved = matches.saveAndFlush(new Match(userId, resume.resumeId(), jobDescription,
                        ai.company(), ai.role(), ai.fitScore(), ai.missingSkills(), ai.suggestedBullets(),
                        ai.provider(), ai.model()));
                outbox.write(Topics.MATCH_EVENTS, EventEnvelope.create(EventTypes.MATCH_COMPLETED, userId,
                        "match-" + saved.getId(), 0, Map.of("matchId", saved.getId(), "fitScore", saved.getFitScore()), json));
                MatchResponse body = MatchResponse.of(saved);
                HttpHeaders headers = new HttpHeaders();
                headers.setLocation(URI.create("/matches/" + saved.getId()));
                idempotency.completeInCurrentTransaction(req, 201, headers, body);
                return ResponseEntity.created(headers.getLocation()).body(body);
            });
        });
    }

    @Transactional(readOnly = true)
    public List<Match> list(long userId) {
        return matches.findTop100ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Match get(long userId, long id) {
        return matches.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Match"));
    }
}