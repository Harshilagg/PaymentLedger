CREATE TABLE account (
    id              UUID PRIMARY KEY,
    owner_id        UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_owner_id ON account (owner_id);

CREATE TABLE wallet (
    id              UUID PRIMARY KEY,
    account_id      UUID NOT NULL REFERENCES account (id),
    currency        VARCHAR(3) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    balance_minor   BIGINT NOT NULL DEFAULT 0,
    reserved_minor  BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallet_account_currency UNIQUE (account_id, currency),
    CONSTRAINT ck_wallet_balance_non_negative CHECK (balance_minor >= 0),
    CONSTRAINT ck_wallet_reserved_non_negative CHECK (reserved_minor >= 0)
);

CREATE INDEX idx_wallet_account_id ON wallet (account_id);
