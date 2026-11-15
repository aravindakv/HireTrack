package com.aravindakv.hiretrack.feed.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedDomainTest {

    @Test
    void queryKeyIgnoresCaseWordOrderAndExtraSpaces() {
        assertThat(QueryKey.of("in", "Bangalore", 25, "Java  Developer", false))
                .isEqualTo(QueryKey.of("IN", " bangalore ", 25, "developer java", false));
        assertThat(QueryKey.of("in", "Bangalore", 25, "java", false))
                .isNotEqualTo(QueryKey.of("in", "Bangalore", 50, "java", false));
    }

    @Test
    void nextRunIsSevenLocalPlusJitterAndAlwaysInTheFuture() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        Instant beforeSeven = Instant.parse("2026-11-18T00:00:00Z");     // 05:30 IST
        Instant next = RunSchedule.next(beforeSeven, kolkata, 7, 42L, 60);
        var local = next.atZone(kolkata);
        assertThat(local.toLocalDate().toString()).isEqualTo("2026-11-18");
        assertThat(local.getHour()).isEqualTo(7);

        Instant afterRun = next.plusSeconds(1);
        assertThat(RunSchedule.next(afterRun, kolkata, 7, 42L, 60).atZone(kolkata).toLocalDate().toString())
                .isEqualTo("2026-11-19");
    }

    @Test
    void jitterIsStablePerUserAndSpreadsUsers() {
        ZoneId utc = ZoneId.of("UTC");
        Instant now = Instant.parse("2026-11-18T00:00:00Z");
        assertThat(RunSchedule.next(now, utc, 7, 1L, 60)).isEqualTo(RunSchedule.next(now, utc, 7, 1L, 60));
        long distinct = java.util.stream.LongStream.range(1, 200)
                .mapToObj(u -> RunSchedule.next(now, utc, 7, u, 60)).distinct().count();
        assertThat(distinct).isGreaterThan(30);
    }

    @Test
    void prefilterRanksBySharedWordsThenRecency() {
        var ranked = Prefilter.top(List.of(
                new Prefilter.Candidate(1, "Senior Java Spring Kafka engineer", 100),
                new Prefilter.Candidate(2, "Graphic designer, Photoshop", 300),
                new Prefilter.Candidate(3, "Java developer", 200),
                new Prefilter.Candidate(4, "Java developer", 250)),
                "Java, Spring Boot, Kafka, PostgreSQL", 3);
        assertThat(ranked).extracting(Prefilter.Ranked::postingId).containsExactly(1L, 4L, 3L);
        assertThat(ranked.get(0).score()).isEqualTo(3);
    }
}