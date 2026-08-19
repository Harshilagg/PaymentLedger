CREATE TABLE outbox_event (
    id              UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload_json    JSONB NOT NULL,
    published       BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_event_unpublished ON outbox_event (created_at) WHERE published = false;
