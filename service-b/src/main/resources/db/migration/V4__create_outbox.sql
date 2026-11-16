CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    aggregate_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_event_pending_idx ON outbox_event (created_at, event_id)
    WHERE published_at IS NULL;
