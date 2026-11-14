package com.aravindakv.hiretrack.common.events;

public final class Topics {
    public static final String IDENTITY_EVENTS = "identity-events";
    public static final String RESUME_EVENTS = "resume-events";
    public static final String MATCH_EVENTS = "match-events";
    public static final String APPLICATION_EVENTS = "application-events";
    public static final String FEED_COMMANDS = "feed-commands";
    public static final String FEED_EVENTS = "feed-events";

    private Topics() {}

    public static String deadLetter(String topic) {
        return topic + ".DLT";
    }
}