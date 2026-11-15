CREATE TABLE resumes (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    content        TEXT        NOT NULL CHECK (length(content) BETWEEN 1 AND 20000),
    source         TEXT        NOT NULL CHECK (source IN ('PDF', 'PASTE')),
    pdf_object_key TEXT,
    is_active      BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX resumes_user ON resumes (user_id, created_at DESC);
-- Spec 7.2: at most one active resume per user
CREATE UNIQUE INDEX resumes_one_active ON resumes (user_id) WHERE is_active;