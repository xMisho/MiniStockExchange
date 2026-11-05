ALTER TABLE exchange.users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE exchange.users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE exchange.users ADD COLUMN alert_emails BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE exchange.email_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES exchange.users(id) ON DELETE CASCADE,
    purpose VARCHAR(10) NOT NULL CHECK (purpose IN ('RESET','VERIFY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);
CREATE INDEX email_tokens_user ON exchange.email_tokens(user_id, purpose, created_at);
CREATE TABLE exchange.email_outbox (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES exchange.users(id) ON DELETE CASCADE,
    dedupe_key VARCHAR(128) NOT NULL UNIQUE,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('RESET','VERIFY','ALERT')),
    payload TEXT,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','SENT','FAILED','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMPTZ,
    provider_id VARCHAR(128),
    failure_code VARCHAR(40)
);
CREATE INDEX email_outbox_pending ON exchange.email_outbox(next_attempt_at) WHERE status IN ('PENDING','SENDING');
