-- Single Postgres instance, two schemas, two least-privilege users, no cross-schema grants -
-- see SPEC.md "Why one instance, not two". Each service's Flyway migrations create their own
-- tables inside their own schema; this script only sets up the isolation boundary.

CREATE SCHEMA IF NOT EXISTS wallet;
CREATE SCHEMA IF NOT EXISTS ledger;

CREATE USER wallet_user WITH PASSWORD 'wallet_pass';
CREATE USER ledger_user WITH PASSWORD 'ledger_pass';

GRANT ALL PRIVILEGES ON SCHEMA wallet TO wallet_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA wallet GRANT ALL PRIVILEGES ON TABLES TO wallet_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA wallet GRANT ALL PRIVILEGES ON SEQUENCES TO wallet_user;

GRANT ALL PRIVILEGES ON SCHEMA ledger TO ledger_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA ledger GRANT ALL PRIVILEGES ON TABLES TO ledger_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA ledger GRANT ALL PRIVILEGES ON SEQUENCES TO ledger_user;

-- Neither user gets USAGE on the other's schema, and neither is a superuser - wallet_user
-- cannot read or write ledger's tables and vice versa, enforced by Postgres, not just by
-- convention.
