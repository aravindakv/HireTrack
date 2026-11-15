package com.aravindakv.hiretrack.application.domain;

public enum Stage {
    WISHLIST, APPLIED, INTERVIEW, OFFER, REJECTED;

    /** Moving here means the application was sent. */
    public boolean countsAsSent() {
        return this == APPLIED || this == INTERVIEW || this == OFFER;
    }

    /** Moving here (after sending) means the company replied. A rejection is a reply. */
    public boolean countsAsReply() {
        return this == INTERVIEW || this == OFFER || this == REJECTED;
    }
}