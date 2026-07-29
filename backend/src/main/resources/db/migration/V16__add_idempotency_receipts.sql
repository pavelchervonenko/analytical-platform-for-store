CREATE TABLE idempotency_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    idempotency_key varchar(100) NOT NULL,
    action varchar(64) NOT NULL,
    resource_identity varchar(256) NOT NULL,
    request_hash varchar(64) NOT NULL CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    response_type varchar(200) NOT NULL,
    response_body text NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX ux_idempotency_receipts_actor_key
    ON idempotency_receipts (actor_id, idempotency_key);
CREATE INDEX ix_idempotency_receipts_expiry
    ON idempotency_receipts (expires_at);

COMMENT ON TABLE idempotency_receipts IS
    'Short-lived transactional replay receipts for high-risk commands.';
COMMENT ON COLUMN idempotency_receipts.idempotency_key IS
    'Opaque caller key scoped to the authenticated actor; never logged.';
COMMENT ON COLUMN idempotency_receipts.request_hash IS
    'SHA-256 of a canonical request body; the request body itself is not retained.';
COMMENT ON COLUMN idempotency_receipts.response_body IS
    'Exact successful response retained only until expires_at for safe timeout retries.';
