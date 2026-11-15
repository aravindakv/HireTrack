CREATE TABLE applications (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    company       TEXT        NOT NULL CHECK (length(company) BETWEEN 1 AND 200),
    role          TEXT        NOT NULL CHECK (length(role) BETWEEN 1 AND 200),
    stage         TEXT        NOT NULL DEFAULT 'WISHLIST'
                  CHECK (stage IN ('WISHLIST', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED')),
    job_url       TEXT,
    applied_date  DATE,
    responded_at  TIMESTAMPTZ,
    notes         TEXT,
    source_type   TEXT        NOT NULL DEFAULT 'MANUAL' CHECK (source_type IN ('MANUAL', 'MATCH', 'FEED')),
    source_id     TEXT,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((source_type = 'MANUAL') = (source_id IS NULL))
);
CREATE INDEX applications_user_stage ON applications (user_id, stage);
-- A match or a feed posting becomes at most one application (replaces the class build's unique FK)
CREATE UNIQUE INDEX applications_one_per_source ON applications (user_id, source_type, source_id)
    WHERE source_id IS NOT NULL;