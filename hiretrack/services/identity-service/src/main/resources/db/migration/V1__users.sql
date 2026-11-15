CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    email         TEXT        NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash TEXT        NOT NULL,
    last_seen_on  DATE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
