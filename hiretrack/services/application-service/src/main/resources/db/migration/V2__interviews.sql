CREATE TABLE interviews (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT      NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    scheduled_at   TIMESTAMPTZ NOT NULL,
    round          TEXT CHECK (length(round) <= 100),
    notes          TEXT,
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX interviews_application ON interviews (application_id);
CREATE INDEX interviews_scheduled_at ON interviews (scheduled_at);