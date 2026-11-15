package com.aravindakv.hiretrack.ai.provider.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.BatchResultsParams;
import com.anthropic.models.messages.batches.BatchRetrieveParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.aravindakv.hiretrack.ai.config.AiProperties;
import com.aravindakv.hiretrack.ai.model.FeedScoreRequest;
import com.aravindakv.hiretrack.ai.model.Prompts;
import com.aravindakv.hiretrack.ai.model.ResultParser;
import com.aravindakv.hiretrack.ai.model.TokenUsage;
import com.aravindakv.hiretrack.ai.provider.AiProvider;
import com.aravindakv.hiretrack.ai.provider.AiRateLimitedException;
import com.aravindakv.hiretrack.ai.provider.AiRejectedException;
import com.aravindakv.hiretrack.ai.provider.AiUpstreamException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AnthropicProvider implements AiProvider {

    private final AnthropicClient matchClient;
    private final AnthropicClient batchClient;
    private final AiProperties.Provider cfg;
    private final ObjectMapper json;

    public AnthropicProvider(AiProperties props, ObjectMapper json) {
        this.cfg = props.anthropic();
        this.json = json;
        this.matchClient = AnthropicOkHttpClient.builder()
                .apiKey(cfg.apiKey()).baseUrl(cfg.baseUrl())
                .timeout(props.matchTimeout()).maxRetries(props.matchMaxRetries()).build();
        this.batchClient = AnthropicOkHttpClient.builder()
                .apiKey(cfg.apiKey()).baseUrl(cfg.baseUrl())
                .timeout(props.feedTimeout()).maxRetries(2).build();
    }

    @Override public String name() { return "anthropic"; }
    @Override public String feedModel() { return cfg.feedModel(); }

    private static OutputConfig schemaConfig(Map<String, Object> schema) {
        JsonOutputFormat.Schema.Builder b = JsonOutputFormat.Schema.builder();
        schema.forEach((k, v) -> b.putAdditionalProperty(k, JsonValue.from(v)));
        return OutputConfig.builder().format(JsonOutputFormat.builder().schema(b.build()).build()).build();
    }

    @Override
    public MatchCompletion match(String resumeText, String jobDescription) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(cfg.matchModel())
                .maxTokens(16_000L)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(Prompts.MATCH_SYSTEM)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(schemaConfig(Prompts.matchSchema()))
                .addUserMessage(Prompts.matchUser(resumeText, jobDescription))
                .build();
        try {
            Message message = matchClient.messages().create(params);
            StopReason stop = message.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stop)) throw new AiRejectedException("AI_DECLINED", "model refused");
            if (StopReason.MAX_TOKENS.equals(stop)) throw new AiRejectedException("AI_TRUNCATED", "hit max tokens");
            var result = ResultParser.match(text(message), json);
            var u = message.usage();
            return new MatchCompletion(result, new TokenUsage(cfg.matchModel(), u.inputTokens(),
                    u.cacheReadInputTokens().orElse(0L), u.outputTokens()));
        } catch (RateLimitException e) {
            throw new AiRateLimitedException("anthropic rate limit", e);
        } catch (AnthropicServiceException | AnthropicIoException e) {
            throw new AiUpstreamException("anthropic call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String submitFeedBatch(List<FeedScoreRequest> items) {
        BatchCreateParams.Builder batch = BatchCreateParams.builder();
        OutputConfig feedSchema = schemaConfig(Prompts.feedSchema());
        for (FeedScoreRequest item : items) {
            batch.addRequest(BatchCreateParams.Request.builder()
                    .customId(item.customId())
                    .params(BatchCreateParams.Request.Params.builder()
                            .model(cfg.feedModel())
                            .maxTokens(16_000L)
                            .system(Prompts.FEED_SYSTEM)
                            .outputConfig(feedSchema)
                            .addUserMessage(Prompts.feedUser(item.resumeText(), item.postings()))
                            .build())
                    .build());
        }
        try {
            return batchClient.messages().batches().create(batch.build()).id();
        } catch (AnthropicServiceException | AnthropicIoException e) {
            throw new AiUpstreamException("anthropic batch submit failed: " + e.getMessage(), e);
        }
    }

    @Override
    public BatchPoll pollFeedBatch(String providerBatchId, List<FeedScoreRequest> submitted) {
        try {
            MessageBatch batch = batchClient.messages().batches()
                    .retrieve(BatchRetrieveParams.builder().messageBatchId(providerBatchId).build());
            if (!batch.processingStatus().equals(MessageBatch.ProcessingStatus.ENDED)) {
                return new BatchPoll(BatchState.IN_PROGRESS, List.of());
            }
            Map<String, FeedScoreRequest> byId = submitted.stream()
                    .collect(Collectors.toMap(FeedScoreRequest::customId, r -> r));
            Map<String, ItemResult> results = new HashMap<>();
            try (StreamResponse<MessageBatchIndividualResponse> stream = batchClient.messages().batches()
                    .resultsStreaming(BatchResultsParams.builder().messageBatchId(providerBatchId).build())) {
                stream.stream().forEach(r -> results.put(r.customId(), toItem(r, byId)));
            }
            for (String id : byId.keySet()) {
                results.putIfAbsent(id, new ItemResult(id, false, List.of(), null, "no result"));
            }
            return new BatchPoll(BatchState.ENDED, new ArrayList<>(results.values()));
        } catch (AnthropicServiceException | AnthropicIoException e) {
            throw new AiUpstreamException("anthropic batch poll failed: " + e.getMessage(), e);
        }
    }

    private ItemResult toItem(MessageBatchIndividualResponse r, Map<String, FeedScoreRequest> byId) {
        String id = r.customId();
        if (!r.result().isSucceeded()) {
            return new ItemResult(id, false, List.of(), null, r.result().type().toString());
        }
        Message m = r.result().asSucceeded().message();
        TokenUsage usage = new TokenUsage(cfg.feedModel(), m.usage().inputTokens(),
                m.usage().cacheReadInputTokens().orElse(0L), m.usage().outputTokens());
        if (StopReason.REFUSAL.equals(m.stopReason().orElse(null))) {
            return new ItemResult(id, false, List.of(), usage, "refused");
        }
        FeedScoreRequest req = byId.get(id);
        if (req == null) return new ItemResult(id, false, List.of(), usage, "unknown custom_id");
        Set<String> sent = req.postings().stream().map(FeedScoreRequest.Posting::postingId).collect(Collectors.toSet());
        try {
            return new ItemResult(id, true, ResultParser.feed(text(m), sent, json), usage, null);
        } catch (AiRejectedException e) {
            return new ItemResult(id, false, List.of(), usage, e.code());
        }
    }

    private static String text(Message m) {
        return m.content().stream().flatMap(b -> b.text().stream()).map(TextBlock::text).collect(Collectors.joining());
    }
}