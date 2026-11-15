package com.aravindakv.hiretrack.feed.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class RunSchedule {

    private RunSchedule() {}

    /**
     * The next run strictly after `now`: runHour:00 local time plus a stable per-user jitter,
     * so users spread across the hour instead of arriving in one spike.
     */
    public static Instant next(Instant now, ZoneId zone, int runHour, long userId, int jitterMinutes) {
        int jitter = (int) Math.floorMod(Long.hashCode(userId * 2654435761L), (long) Math.max(1, jitterMinutes));
        LocalDate today = now.atZone(zone).toLocalDate();
        ZonedDateTime candidate = today.atTime(LocalTime.of(runHour, 0)).plusMinutes(jitter).atZone(zone);
        if (!candidate.toInstant().isAfter(now)) {
            candidate = today.plusDays(1).atTime(LocalTime.of(runHour, 0)).plusMinutes(jitter).atZone(zone);
        }
        return candidate.toInstant();
    }
}