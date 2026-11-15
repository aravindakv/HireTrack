package com.aravindakv.hiretrack.ai.provider;

/** The provider answered, but the answer is unusable: refusal, cut off, or invalid JSON. Not a provider outage. */
public class AiRejectedException extends RuntimeException {
    private final String code;

    public AiRejectedException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String code() { return code; }
}