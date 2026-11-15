package com.aravindakv.hiretrack.feed.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

/** Users with the same normalised search share one Adzuna fetch. */
public final class QueryKey {

    private QueryKey() {}

    public static String of(String country, String location, int radiusKm, String keywords, boolean remoteOk) {
        String normalisedKeywords = Arrays.stream(keywords.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(w -> !w.isBlank()).sorted().collect(Collectors.joining(" "));
        String raw = country.toLowerCase(Locale.ROOT) + "|" + location.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                + "|" + radiusKm + "|" + normalisedKeywords + "|" + remoteOk;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}