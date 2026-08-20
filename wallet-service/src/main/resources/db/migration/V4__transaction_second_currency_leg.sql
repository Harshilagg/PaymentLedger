-- Null for same-currency transactions (the common case), populated only for a cross-currency
-- transfer/reversal, where amount_minor/currency is the FROM leg and these are the TO leg.
ALTER TABLE transaction
    ADD COLUMN to_amount_minor BIGINT,
    ADD COLUMN to_currency VARCHAR(3);

ALTER TABLE transaction
    ADD CONSTRAINT ck_transaction_to_amount_positive CHECK (to_amount_minor IS NULL OR to_amount_minor > 0);
