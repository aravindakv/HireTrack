package com.aravindakv.hiretrack.ai.provider.openai;

import com.aravindakv.hiretrack.ai.config.AiProperties;
import com.aravindakv.hiretrack.ai.model.FeedScoreRequest;
import com.aravindakv.hiretrack.ai.model.MatchResult;
import com.aravindakv.hiretrack.ai.model.Prompts;
import com.aravindakv.hiretrack.ai.model.ResultParser;
import com.aravindakv.hiretrack.ai.model.TokenUsage;
import com.aravindakv.hiretrack.ai.provider.AiProvider;
import com.aravindakv.hiretrack.ai.provider.AiRateLimitedException;
import com.aravindakv.hiretrack.ai.provider.AiRejectedException;
import com.aravindakv.hiretrack.ai.provider.AiUpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.models.batches.Batch;
import com.openai.models.batches.BatchCreateParams;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FileObject;
import com.openai.models.files.FilePurpose;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class OpenAiProvider implements AiProvider {

    private final OpenAIClient matchClient;
    private final OpenAIClient batchClient;
    private final AiProperties.Provider cfg;
    private final ObjectMapper json;

    public OpenAiProvider(AiProperties props, ObjectMapper json) {
        this.cfg = props.openai();
        this.json = json;
        this.matchClient = OpenAIOkHttpClient.builder()
                .apiKey(cfg.apiKey())
                .baseUrl(cfg.baseUrl())
                .timeout(props.matchTimeout())
                .maxRetries(props.matchMaxRetries())       // retries 408/409/429/5xx and connection errors
                .build();
        this.batchClient = OpenAIOkHttpClient.builder()
                .apiKey(cfg.apiKey())
                .baseUrl(cfg.baseUrl())
                .timeout(props.feedTimeout())
                .maxRetries(2)
                .build();
    }

    @Override public String name() { return "openai"; }
    @Override public String feedModel() { return cfg.feedModel(); }

    @Override
    public MatchCompletion match(String resumeText, String jobDescription) {
        StructuredChatCompletionCreateParams<OpenAiMatchOutput> params = ChatCompletionCreateParams.builder()
                .model(cfg.matchModel())
                .maxCompletionTokens(16_000L)        // room for reasoning tokens; the answer itself is small
                .addDeveloperMessage(Prompts.MATCH_SYSTEM)
                .addUserMessage(Prompts.matchUser(resumeText, jobDescription))
                .responseFormat(OpenAiMatchOutput.class)
                .build();
        try {
            var completion = matchClient.chat().completions().create(params);
            var choice = completion.choices().get(0);
            if (choice.message().refusal().isPresent()) {
                throw new AiRejectedException("AI_DECLINED", "model refused");
            }
            if (choice.finishReason().equals(ChatCompletion.Choice.FinishReason.LENGTH)) {
                throw new AiRejectedException("AI_TRUNCATED", "output hit the token limit");
            }
            OpenAiMatchOutput out = choice.message().content()
                    .orElseThrow(() -> new AiRejectedException("AI_INVALID_OUTPUT", "empty content"));
            MatchResult result = ResultParser.match(json.writeValueAsString(Map.of(
                    "fit_score", out.fitScore,
                    "missing_skills", out.missingSkills == null ? List.of() : out.missingSkills,
                    "suggested_bullets", out.suggestedBullets == null ? List.of() : out.suggestedBullets,
                    "company", out.company == null ? "" : out.company,
                    "role", out.role == null ? "" : out.role)), json);
            var usage = completion.usage().orElse(null);
            TokenUsage tokens = usage == null
                    ? new TokenUsage(cfg.matchModel(), 0, 0, 0)
                    : new TokenUsage(cfg.matchModel(), usage.promptTokens(),
                        usage.promptTokensDetails().flatMap(d -> d.cachedTokens()).orElse(0L),
                        usage.completionTokens());
            return new MatchCompletion(result, tokens);
        } catch (RateLimitException e) {
            throw new AiRateLimitedException("openai rate limit", e);
        } catch (OpenAIServiceException | OpenAIIoException e) {
            throw new AiUpstreamException("openai call failed: " + e.getMessage(), e);
        } catch (tools.jackson.core.JacksonException e) {
            throw new AiRejectedException("AI_INVALID_OUTPUT", "could not re-serialise output");
        }
    }

    /**
     * Batch API: one JSONL line per feed run, each a full Chat Completions request with a strict JSON schema.
     * The file is written by hand because batch requests are plain JSON.
     */
    @Override
    public String submitFeedBatch(List<FeedScoreRequest> items) {
        try {
            Path file = Files.createTempFile("feed-batch-", ".jsonl");
            try {
                List<String> lines = new ArrayList<>();
                for (FeedScoreRequest item : items) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("model", cfg.feedModel());
                    body.put("max_completion_tokens", 16_000);
                    body.put("messages", List.of(
                            Map.of("role", "developer", "content", Prompts.FEED_SYSTEM),
                            Map.of("role", "user", "content", Prompts.feedUser(item.resumeText(), item.postings()))));
                    body.put("response_format", Map.of("type", "json_schema", "json_schema",
                            Map.of("name", "feed_scores", "strict", true, "schema", Prompts.feedSchema())));
                    lines.add(json.writeValueAsString(Map.of(
                            "custom_id", item.customId(), "method", "POST",
                            "url", "/v1/chat/completions", "body", body)));
                }
                Files.write(file, lines, StandardCharsets.UTF_8);
                FileObject uploaded = batchClient.files().create(FileCreateParams.builder()
                        .purpose(FilePurpose.BATCH).file(file).build());
                Batch batch = batchClient.batches().create(BatchCreateParams.builder()
                        .inputFileId(uploaded.id())
                        .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                        .completionWindow(BatchCreateParams.CompletionWindow._24H)
                        .build());
                return batch.id();
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new AiUpstreamException("could not write batch file", e);
        } catch (OpenAIServiceException | OpenAIIoException e) {
            throw new AiUpstreamException("openai batch submit failed: " + e.getMessage(), e);
        }
    }

    @Override
    public BatchPoll pollFeedBatch(String providerBatchId, List<FeedScoreRequest> submitted) {
        try {
            Batch batch = batchClient.batches().retrieve(providerBatchId);
            String status = batch.status().asString();
            switch (status) {
                case "validating", "in_progress", "finalizing", "cancelling":
                    return new BatchPoll(BatchState.IN_PROGRESS, List.of());
                case "completed", "expired", "cancelled", "failed":
                    break;
                default:
                    return new BatchPoll(BatchState.IN_PROGRESS, List.of());
            }
            Map<String, FeedScoreRequest> byId = submitted.stream()
                    .collect(Collectors.toMap(FeedScoreRequest::customId, r -> r));
            Map<String, ItemResult> results = new HashMap<>();
            if (batch.outputFileId().isPresent()) {
                readLines(batch.outputFileId().get(), line -> {
                    ItemResult r = parseOutputLine(line, byId);
                    results.put(r.customId(), r);
                });
            }
            if (batch.errorFileId().isPresent()) {
                readLines(batch.errorFileId().get(), line -> {
                    String id = line.path("custom_id").asString();
                    results.putIfAbsent(id, new ItemResult(id, false, List.of(), null,
                            line.path("error").path("message").asString("request failed")));
                });
            }
            for (String id : byId.keySet()) {                   // expired / cancelled leftovers
                results.putIfAbsent(id, new ItemResult(id, false, List.of(), null, "no result: batch " + status));
            }
            BatchState state = "failed".equals(status) ? BatchState.FAILED : BatchState.ENDED;
            return new BatchPoll(state, new ArrayList<>(results.values()));
        } catch (OpenAIServiceException | OpenAIIoException e) {
            throw new AiUpstreamException("openai batch poll failed: " + e.getMessage(), e);
        }
    }

    private ItemResult parseOutputLine(JsonNode line, Map<String, FeedScoreRequest> byId) {
        String id = line.path("custom_id").asString();
        JsonNode response = line.path("response");
        if (response.path("status_code").asInt() != 200) {
            return new ItemResult(id, false, List.of(), null, "status " + response.path("status_code").asInt());
        }
        JsonNode body = response.path("body");
        JsonNode message = body.path("choices").path(0).path("message");
        JsonNode u = body.path("usage");
        TokenUsage usage = new TokenUsage(body.path("model").asString(cfg.feedModel()),
                u.path("prompt_tokens").asLong(), u.path("prompt_tokens_details").path("cached_tokens").asLong(),
                u.path("completion_tokens").asLong());
        if (!message.path("refusal").isMissingNode() && !message.path("refusal").isNull()) {
            return new ItemResult(id, false, List.of(), usage, "refused");
        }
        if ("length".equals(body.path("choices").path(0).path("finish_reason").asString())) {
            return new ItemResult(id, false, List.of(), usage, "truncated");
        }
        FeedScoreRequest req = byId.get(id);
        if (req == null) return new ItemResult(id, false, List.of(), usage, "unknown custom_id");
        Set<String> sent = req.postings().stream().map(FeedScoreRequest.Posting::postingId).collect(Collectors.toSet());
        try {
            return new ItemResult(id, true, ResultParser.feed(message.path("content").asString(), sent, json), usage, null);
        } catch (AiRejectedException e) {
            return new ItemResult(id, false, List.of(), usage, e.code());
        }
    }

    private void readLines(String fileId, java.util.function.Consumer<JsonNode> onLine) {
        try (HttpResponse response = batchClient.files().content(fileId);
             BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) onLine.accept(json.readTree(line));
            }
        } catch (IOException e) {
            throw new AiUpstreamException("could not read batch file " + fileId, e);
        }
    }
}