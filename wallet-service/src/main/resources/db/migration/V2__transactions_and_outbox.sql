CREATE TABLE transaction (
    id                      UUID PRIMARY KEY,
    type                    VARCHAR(20) NOT NULL,
    from_wallet_id          UUID REFERENCES wallet (id),
    to_wallet_id            UUID REFERENCES wallet (id),
    amount_minor            BIGINT NOT NULL,
    currency                VARCHAR(3) NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    idempotency_key         VARCHAR(255),
    original_transaction_id UUID REFERENCES transaction (id),
    failure_reason          VARCHAR(255),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    CONSTRAINT ck_transaction_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX idx_transaction_from_wallet ON transaction (from_wallet_id);
CREATE INDEX idx_transaction_to_wallet ON transaction (to_wallet_id);
CREATE INDEX idx_transaction_idempotency_key ON transaction (idempotency_key);

CREATE TABLE idempotency_record (
    key             VARCHAR(255) PRIMARY KEY,
    request_hash    VARCHAR(64) NOT NULL,
    response_body   TEXT,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idempotency_record_expires_at ON idempotency_record (expires_at);

CREATE TABLE outbox_event (
    id              UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload_json    JSONB NOT NULL,
    published       BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_event_unpublished ON outbox_event (created_at) WHERE published = false;

CREATE TABLE exchange_rate (
    from_currency   VARCHAR(3) NOT NULL,
    to_currency     VARCHAR(3) NOT NULL,
    rate            NUMERIC(18, 8) NOT NULL,
    effective_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (from_currency, to_currency, effective_at)
);
