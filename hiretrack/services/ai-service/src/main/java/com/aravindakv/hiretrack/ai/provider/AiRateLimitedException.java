package com.aravindakv.hiretrack.ai.provider;

/** The provider's rate limit was hit after the SDK's own retries. */
public class AiRateLimitedException extends RuntimeException {
    public AiRateLimitedException(String detail, Throwable cause) {
        super(detail, cause);
    }
}