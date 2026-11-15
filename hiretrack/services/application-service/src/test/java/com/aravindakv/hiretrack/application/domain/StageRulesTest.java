package com.aravindakv.hiretrack.application.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class StageRulesTest {

    static final LocalDate DAY1 = LocalDate.of(2026, 11, 2);
    static final LocalDate DAY5 = LocalDate.of(2026, 11, 6);
    static final Instant T1 = Instant.parse("2026-11-02T10:00:00Z");
    static final Instant T5 = Instant.parse("2026-11-06T10:00:00Z");

    private JobApplication fresh() {
        return new JobApplication(1L, "Acme", "Engineer", null, null, SourceType.MANUAL, null);
    }

    @Test
    void newApplicationStartsInWishlistWithNoDates() {
        JobApplication a = fresh();
        assertThat(a.getStage()).isEqualTo(Stage.WISHLIST);
        assertThat(a.getAppliedDate()).isNull();
        assertThat(a.getRespondedAt()).isNull();
    }

    @Test
    void movingToAppliedSetsAppliedDate() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
        assertThat(a.getRespondedAt()).isNull();
    }

    @Test
    void skippingFromWishlistToInterviewSetsBothDates() {
        JobApplication a = fresh();
        a.moveTo(Stage.INTERVIEW, DAY1, T1);
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
        assertThat(a.getRespondedAt()).isEqualTo(T1);
    }

    @Test
    void replyAfterApplyingSetsRespondedAtOnce() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        a.moveTo(Stage.INTERVIEW, DAY5, T5);
        a.moveTo(Stage.OFFER, DAY5.plusDays(3), T5.plusSeconds(86400 * 3));
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
        assertThat(a.getRespondedAt()).isEqualTo(T5);          // the first reply, not the offer
    }

    @Test
    void rejectionAfterApplyingCountsAsReply() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        a.moveTo(Stage.REJECTED, DAY5, T5);
        assertThat(a.getRespondedAt()).isEqualTo(T5);
    }

    @Test
    void rejectedFromWishlistIsNeitherSentNorReplied() {
        JobApplication a = fresh();
        a.moveTo(Stage.REJECTED, DAY1, T1);
        assertThat(a.getAppliedDate()).isNull();
        assertThat(a.getRespondedAt()).isNull();
    }

    @Test
    void movingBackToWishlistClearsBothDates() {
        JobApplication a = fresh();
        a.moveTo(Stage.INTERVIEW, DAY1, T1);
        a.moveTo(Stage.WISHLIST, DAY5, T5);
        assertThat(a.getAppliedDate()).isNull();
        assertThat(a.getRespondedAt()).isNull();
    }

    @Test
    void movingOutOfRejectedKeepsDates() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        a.moveTo(Stage.REJECTED, DAY5, T5);
        a.moveTo(Stage.INTERVIEW, DAY5.plusDays(1), T5.plusSeconds(86400));
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
        assertThat(a.getRespondedAt()).isEqualTo(T5);
    }

    @Test
    void movingToTheCurrentStageIsANoOp() {
        JobApplication a = fresh();
        a.moveTo(Stage.APPLIED, DAY1, T1);
        assertThat(a.moveTo(Stage.APPLIED, DAY5, T5)).isFalse();
        assertThat(a.getAppliedDate()).isEqualTo(DAY1);
    }
}