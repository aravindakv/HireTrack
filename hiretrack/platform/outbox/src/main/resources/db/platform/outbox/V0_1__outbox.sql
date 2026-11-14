CREATE TABLE outbox (
    id           UUID        PRIMARY KEY,
    topic        TEXT        NOT NULL,
    record_key   TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;