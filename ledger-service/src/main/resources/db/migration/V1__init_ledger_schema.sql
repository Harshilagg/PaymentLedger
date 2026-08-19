CREATE TABLE ledger_entry (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL,
    wallet_id       UUID NOT NULL,
    account_id      UUID NOT NULL,
    direction       VARCHAR(10) NOT NULL,
    amount_minor    BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entry_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_ledger_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Domain invariant, not just a duplicate-delivery guard: a given transaction moves funds
    -- through a given wallet in a given direction exactly once. This is what makes redelivering
    -- transaction-initiated events a safe no-op - see SPEC.md "Idempotent event consumption".
    CONSTRAINT uq_ledger_entry_txn_wallet_direction UNIQUE (transaction_id, wallet_id, direction)
);

CREATE INDEX idx_ledger_entry_transaction_id ON ledger_entry (transaction_id);
CREATE INDEX idx_ledger_entry_wallet_id ON ledger_entry (wallet_id);
