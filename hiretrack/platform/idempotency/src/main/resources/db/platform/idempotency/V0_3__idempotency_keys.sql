CREATE TABLE idempotency_keys (
    user_id          BIGINT      NOT NULL,
    idem_key         TEXT        NOT NULL,
    operation        TEXT        NOT NULL,
    request_hash     TEXT        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status  INT,
    response_headers JSONB,
    response_body    JSONB,
    locked_until     TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);
CREATE INDEX idempotency_keys_expires_at ON idempotency_keys (expires_at);