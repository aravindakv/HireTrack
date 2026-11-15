package com.aravindakv.hiretrack.analytics.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class DashboardMath {

    private DashboardMath() {}

    public record Range(Instant from, Instant to) {}            // [from, to)

    /** Spec 3.5: Monday 00:00 to the next Monday 00:00 in the viewer's timezone, as UTC instants. */
    public static Range currentWeek(Instant now, ZoneId zone) {
        ZonedDateTime monday = now.atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone);
        return new Range(monday.toInstant(), monday.plusWeeks(1).toInstant());
    }

    /** replied ÷ sent; null when nothing has been sent (the UI shows "—"). */
    public static Double responseRate(long sent, long replied) {
        return sent == 0 ? null : (double) replied / sent;
    }
}