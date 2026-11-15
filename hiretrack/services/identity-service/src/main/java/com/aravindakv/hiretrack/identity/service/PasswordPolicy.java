package com.aravindakv.hiretrack.identity.service;

import com.aravindakv.hiretrack.common.web.ApiException;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    private PasswordPolicy() {}

    /** Spec 3.1: 8-72 bytes. bcrypt ignores everything after 72 bytes, so longer passwords are refused. */
    public static void check(String password) {
        int bytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 8 || bytes > 72) {
            throw ApiException.badRequest("PASSWORD_LENGTH", "Password must be 8 to 72 bytes long.");
        }
    }
}