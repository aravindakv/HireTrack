CREATE TABLE app_projection (
    application_id BIGINT      PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    stage          TEXT        NOT NULL,
    applied_date   DATE,
    responded_at   TIMESTAMPTZ,
    version        BIGINT      NOT NULL          -- aggregateVersion of the newest applied event
);
CREATE INDEX app_projection_user_stage ON app_projection (user_id, stage);

CREATE TABLE interview_projection (
    interview_id   BIGINT      PRIMARY KEY,
    application_id BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    scheduled_at   TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL
);
CREATE INDEX interview_projection_user_time ON interview_projection (user_id, scheduled_at);
CREATE INDEX interview_projection_application ON interview_projection (application_id);

-- Tombstones: an older event arriving after a delete must not bring the row back
CREATE TABLE deleted_aggregates (
    aggregate_id TEXT        PRIMARY KEY,        -- "application-12", "interview-5"
    deleted_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);