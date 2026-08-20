-- Seeded static FX rates for v1 - no live rate feed, see SPEC.md non-goals.
INSERT INTO exchange_rate (from_currency, to_currency, rate, effective_at) VALUES
    ('USD', 'EUR', 0.92000000, '2026-01-01T00:00:00Z'),
    ('EUR', 'USD', 1.08700000, '2026-01-01T00:00:00Z'),
    ('USD', 'GBP', 0.79000000, '2026-01-01T00:00:00Z'),
    ('GBP', 'USD', 1.26580000, '2026-01-01T00:00:00Z'),
    ('EUR', 'GBP', 0.85870000, '2026-01-01T00:00:00Z'),
    ('GBP', 'EUR', 1.16460000, '2026-01-01T00:00:00Z');
