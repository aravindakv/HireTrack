package com.aravindakv.hiretrack.ai;

/** Response bodies shaped like the OpenAI REST API, for WireMock. */
public final class OpenAiStubs {

    private OpenAiStubs() {}

    public static String chatCompletion(String contentJson, String finishReason, String refusal) {
        String content = contentJson == null ? "null" : "\"" + contentJson.replace("\"", "\\\"") + "\"";
        String ref = refusal == null ? "null" : "\"" + refusal + "\"";
        return """
                {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"test-match-model",
                 "choices":[{"index":0,"logprobs":null,"finish_reason":"%s",
                             "message":{"role":"assistant","content":%s,"refusal":%s}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500,
                          "prompt_tokens_details":{"cached_tokens":200}}}
                """.formatted(finishReason, content, ref);
    }

    public static final String VALID_MATCH =
            "{\"fit_score\":64,\"missing_skills\":[\"Kubernetes\",\"AWS\"],\"suggested_bullets\":[\"b1\",\"b2\",\"b3\"],"
            + "\"company\":\"Acme\",\"role\":\"Backend Engineer\"}";
}