package com.aravindakv.hiretrack.common.events;

public final class EventTypes {
    public static final String USER_REGISTERED = "user.registered";
    public static final String USER_SEEN = "user.seen";
    public static final String RESUME_ACTIVATED = "resume.activated";
    public static final String MATCH_COMPLETED = "match.completed";
    public static final String APPLICATION_CREATED = "application.created";
    public static final String APPLICATION_UPDATED = "application.updated";
    public static final String APPLICATION_STAGE_CHANGED = "application.stage-changed";
    public static final String APPLICATION_DELETED = "application.deleted";
    public static final String INTERVIEW_SCHEDULED = "interview.scheduled";
    public static final String INTERVIEW_UPDATED = "interview.updated";
    public static final String INTERVIEW_DELETED = "interview.deleted";
    public static final String FEED_RUN_REQUESTED = "feed.run-requested";
    public static final String FEED_READY = "feed.ready";

    private EventTypes() {}
}