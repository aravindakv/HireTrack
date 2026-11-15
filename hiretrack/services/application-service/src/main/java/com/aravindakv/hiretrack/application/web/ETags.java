package com.aravindakv.hiretrack.application.web;

import com.aravindakv.hiretrack.common.web.ApiException;

final class ETags {

    private ETags() {}

    static String of(long version) {
        return "\"" + version + "\"";
    }

    /** Accepts "3", "\"3\"" and W/"3". */
    static long parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.preconditionRequired("Send If-Match with the ETag you loaded.");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) v = v.substring(2);
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_IF_MATCH", "If-Match must be the ETag you loaded.");
        }
    }
}