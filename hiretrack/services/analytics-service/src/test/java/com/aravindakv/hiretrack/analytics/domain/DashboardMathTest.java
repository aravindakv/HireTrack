package com.aravindakv.hiretrack.analytics.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardMathTest {

    @Test
    void weekStartsMondayMidnightInTheViewersZone() {
        // Wednesday 2026-11-18 10:00 in Kolkata
        var r = DashboardMath.currentWeek(Instant.parse("2026-11-18T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
        assertThat(r.from()).isEqualTo(Instant.parse("2026-11-15T18:30:00Z"));    // Mon 16 Nov 00:00 IST
        assertThat(r.to()).isEqualTo(Instant.parse("2026-11-22T18:30:00Z"));      // Mon 23 Nov 00:00 IST
    }

    @Test
    void sundayLateEveningLocalIsStillTheSameWeek() {
        // Sunday 22 Nov 23:30 IST = 18:00 UTC: already "Monday" nowhere near, still this week locally
        var r = DashboardMath.currentWeek(Instant.parse("2026-11-22T18:00:00Z"), ZoneId.of("Asia/Kolkata"));
        assertThat(r.from()).isEqualTo(Instant.parse("2026-11-15T18:30:00Z"));
    }

    @Test
    void sameInstantIsADifferentWeekInAnotherZone() {
        Instant mondayMorningTokyo = Instant.parse("2026-11-22T23:30:00Z");       // Mon 08:30 Tokyo, Sun 15:30 New York
        assertThat(DashboardMath.currentWeek(mondayMorningTokyo, ZoneId.of("Asia/Tokyo")).from())
                .isEqualTo(Instant.parse("2026-11-22T15:00:00Z"));
        assertThat(DashboardMath.currentWeek(mondayMorningTokyo, ZoneId.of("America/New_York")).from())
                .isEqualTo(Instant.parse("2026-11-16T05:00:00Z"));
    }

    @Test
    void responseRateIsNullBeforeAnythingIsSent() {
        assertThat(DashboardMath.responseRate(0, 0)).isNull();
        assertThat(DashboardMath.responseRate(4, 1)).isEqualTo(0.25);
    }
}