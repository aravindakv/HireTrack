package com.aravindakv.hiretrack.ai.provider;

/** The provider failed: timeout, 5xx, network. Counts towards the circuit breaker. */
public class AiUpstreamException extends RuntimeException {
    public AiUpstreamException(String detail, Throwable cause) {
        super(detail, cause);
    }
}