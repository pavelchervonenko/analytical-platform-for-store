CREATE TABLE livesklad_webhook_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_kind text NOT NULL CHECK (
        webhook_kind IN ('SALE_RETURN', 'ORDER_RETURN')
    ),
    event_id varchar(256) NOT NULL,
    action_id text,
    action_group_id text,
    action_name text,
    payload jsonb NOT NULL,
    payload_sha256 varchar(64) NOT NULL CHECK (length(payload_sha256) = 64),
    last_payload_sha256 varchar(64) NOT NULL CHECK (
        length(last_payload_sha256) = 64
    ),
    payload_mismatch boolean NOT NULL DEFAULT false,
    delivery_count integer NOT NULL DEFAULT 1 CHECK (delivery_count > 0),
    processing_status text NOT NULL DEFAULT 'RECEIVED' CHECK (
        processing_status IN (
            'RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'IGNORED'
        )
    ),
    processing_attempt_count integer NOT NULL DEFAULT 0 CHECK (
        processing_attempt_count >= 0
    ),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_owner text,
    lease_until timestamptz,
    processed_at timestamptz,
    error_code text,
    error_summary text,
    first_received_at timestamptz NOT NULL DEFAULT now(),
    last_received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (webhook_kind, event_id),
    CHECK (last_received_at >= first_received_at),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((processing_status = 'PROCESSED') = (processed_at IS NOT NULL))
);

CREATE INDEX ix_livesklad_webhook_receipts_processing
    ON livesklad_webhook_receipts (
        processing_status,
        available_at,
        first_received_at
    )
    WHERE processing_status IN ('RECEIVED', 'FAILED');

CREATE INDEX ix_livesklad_webhook_receipts_received
    ON livesklad_webhook_receipts (webhook_kind, first_received_at DESC);

COMMENT ON TABLE livesklad_webhook_receipts IS
    'Durable LiveSklad webhook inbox with at-least-once delivery deduplication.';

COMMENT ON COLUMN livesklad_webhook_receipts.payload IS
    'Exact selected webhook data; never exposed through a public application API.';
