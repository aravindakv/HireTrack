package com.aravindakv.hiretrack.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identifies one logical user action.
 * operation   a short name for metrics, e.g. "create-application"
 * requestHash SHA-256 of method, path and body: the same key with a different body is rejected
 */
public record IdempotentRequest(String operation, long userId, String key, String requestHash) {

    public static IdempotentRequest of(String operation, long userId, String key,
                                       String method, String path, Object body, ObjectMapper json) {
        try {
            String bodyJson = body == null ? "" : json.writeValueAsString(body);
            return new IdempotentRequest(operation, userId, key,
                    sha256((method + " " + path + "\n" + bodyJson).getBytes(StandardCharsets.UTF_8)));
        } catch (JacksonException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static IdempotentRequest ofBytes(String operation, long userId, String key,
                                            String method, String path, byte[] body) {
        byte[] prefix = (method + " " + path + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(body, 0, all, prefix.length, body.length);
        return new IdempotentRequest(operation, userId, key, sha256(all));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}