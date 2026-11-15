-- Idempotency for real-time calls: one paid call per caller requestId
CREATE TABLE ai_requests (
    request_id  TEXT        PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    operation   TEXT        NOT NULL,
    status      TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response    JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per paid call (real-time) or per batch item
CREATE TABLE ai_usage (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usage_key           TEXT          NOT NULL UNIQUE,     -- requestId, or batchId:customId
    user_id             BIGINT        NOT NULL,
    provider            TEXT          NOT NULL,
    operation           TEXT          NOT NULL CHECK (operation IN ('MATCH', 'FEED_SCORE')),
    model               TEXT          NOT NULL,
    input_tokens        INT           NOT NULL,
    cached_input_tokens INT           NOT NULL DEFAULT 0,
    output_tokens       INT           NOT NULL,
    cost_usd            NUMERIC(12,6) NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ai_usage_user_day ON ai_usage (user_id, created_at);

CREATE TABLE ai_batches (
    id                UUID        PRIMARY KEY,
    submission_id     TEXT        NOT NULL UNIQUE,       -- caller's idempotency key
    provider          TEXT        NOT NULL,
    provider_batch_id TEXT        NOT NULL,
    model             TEXT        NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN ('SUBMITTED', 'ENDED', 'FAILED')),
    request_count     INT         NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at          TIMESTAMPTZ
);
CREATE INDEX ai_batches_open ON ai_batches (status) WHERE status = 'SUBMITTED';

CREATE TABLE ai_batch_items (
    batch_id   UUID   NOT NULL REFERENCES ai_batches(id) ON DELETE CASCADE,
    custom_id  TEXT   NOT NULL,
    user_id    BIGINT NOT NULL,
    status     TEXT   NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    result     JSONB,
    error      TEXT,
    PRIMARY KEY (batch_id, custom_id)
);

-- ShedLock: only one pod polls batches at a time
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);