CREATE TABLE search_profiles (
    user_id            BIGINT      PRIMARY KEY,
    keywords           TEXT        NOT NULL CHECK (length(keywords) BETWEEN 1 AND 100),
    country            TEXT        NOT NULL CHECK (country ~ '^[a-z]{2}$'),
    location           TEXT        NOT NULL CHECK (length(location) BETWEEN 1 AND 100),
    radius_km          INT         NOT NULL DEFAULT 25 CHECK (radius_km BETWEEN 5 AND 100),
    remote_ok          BOOLEAN     NOT NULL DEFAULT false,
    enabled            BOOLEAN     NOT NULL DEFAULT true,
    timezone           TEXT        NOT NULL,
    query_key          TEXT        NOT NULL,
    next_run_at        TIMESTAMPTZ NOT NULL,
    last_run_at        TIMESTAMPTZ,
    last_manual_run_at TIMESTAMPTZ,
    last_error         TEXT,
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX search_profiles_due ON search_profiles (next_run_at) WHERE enabled;

-- Fed by user.seen / user.registered from identity-service
CREATE TABLE user_activity (
    user_id      BIGINT PRIMARY KEY,
    last_seen_on DATE   NOT NULL
);

-- Shared catalog: one row per posting, whoever saw it first
CREATE TABLE postings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source        TEXT        NOT NULL,
    external_id   TEXT        NOT NULL,
    title         TEXT        NOT NULL,
    company       TEXT,
    location      TEXT,
    url           TEXT        NOT NULL,
    snippet       TEXT        NOT NULL,
    salary_min    INT,
    salary_max    INT,
    posted_at     TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);

CREATE TABLE feed_runs (
    id            UUID        PRIMARY KEY,
    run_key       TEXT        NOT NULL UNIQUE,  -- sched:<user>:<date> or manual:<user>:<yyyy-mm-ddThh>
    user_id       BIGINT      NOT NULL,
    trigger       TEXT        NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    status        TEXT        NOT NULL CHECK (status IN
                  ('REQUESTED', 'READY_TO_SCORE', 'SUBMITTING', 'SCORING', 'DONE', 'NOTHING_NEW', 'FAILED')),
    resume_id     BIGINT,
    resume_text   TEXT,                         -- snapshot for scoring; cleared when the run ends
    submission_id TEXT,
    ai_batch_id   TEXT,
    attempts      INT         NOT NULL DEFAULT 0,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX feed_runs_status ON feed_runs (status, updated_at);
CREATE INDEX feed_runs_user ON feed_runs (user_id, created_at DESC);

-- Per-user view of a posting. The PK makes "already seen" a cheap ON CONFLICT check.
CREATE TABLE feed_items (
    user_id         BIGINT      NOT NULL,
    posting_id      BIGINT      NOT NULL REFERENCES postings(id),
    run_id          UUID        NOT NULL,
    resume_id       BIGINT      NOT NULL,
    prefilter_score INT         NOT NULL DEFAULT 0,
    fit_score       INT         CHECK (fit_score BETWEEN 0 AND 100),
    missing_skills  JSONB,
    reason          TEXT,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'FILTERED', 'NEW', 'SAVED', 'DISMISSED')),
    application_id  BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, posting_id)
);
CREATE INDEX feed_items_feed ON feed_items (user_id, status, fit_score DESC);
CREATE INDEX feed_items_run ON feed_items (run_id);

CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);