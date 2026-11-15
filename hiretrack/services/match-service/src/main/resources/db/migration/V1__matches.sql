CREATE TABLE matches (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    resume_id         BIGINT      NOT NULL,           -- checked through resume-service, no cross-service FK
    job_description   TEXT        NOT NULL CHECK (length(job_description) BETWEEN 1 AND 20000),
    company           TEXT,
    role              TEXT,
    fit_score         INT         NOT NULL CHECK (fit_score BETWEEN 0 AND 100),
    missing_skills    JSONB       NOT NULL,
    suggested_bullets JSONB       NOT NULL,
    ai_provider       TEXT        NOT NULL,
    ai_model          TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX matches_user ON matches (user_id, created_at DESC);