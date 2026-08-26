-- Real authentication replaces the v1 mock token endpoint (see SPEC.md non-goals, now closed).
-- app_user.id IS the owner id already stored on account.owner_id, so no existing table changes
-- and every account created under the mock flow keeps resolving to the same owner.

CREATE TABLE app_user (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(72) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

-- Only the sha-256 hex digest of a refresh token is ever stored - the raw token exists solely in
-- the response body that returned it. A database leak therefore yields nothing replayable.
CREATE TABLE refresh_token (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES app_user (id),
    token_hash      CHAR(64) NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT false,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_user_id ON refresh_token (user_id);

-- Backfill: one user per distinct pre-existing owner_id. Flyway cannot call a Spring bean, so the
-- bcrypt hash is a literal generated once from the dev password documented in README.md. No real
-- email exists for this data, so one is synthesized in the reserved .invalid TLD (RFC 2606) to
-- guarantee it can never collide with a genuine address someone later registers.
INSERT INTO app_user (id, email, password_hash)
SELECT DISTINCT owner_id,
       owner_id || '@example.invalid',
       '$2y$10$yTIuUWbp5zLFpRvOBcj0RunM8yj3iN6UiavGT4HIGWudlKr88eHkG'
FROM account
ON CONFLICT (id) DO NOTHING;
