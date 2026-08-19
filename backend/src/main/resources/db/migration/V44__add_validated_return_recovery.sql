ALTER TABLE livesklad_webhook_receipts
    ADD COLUMN recovery_requested_by uuid
        REFERENCES app_users(id),
    ADD COLUMN recovery_idempotency_key varchar(100),
    ADD COLUMN recovery_expected_document_number varchar(128),
    ADD COLUMN recovery_expected_net_amount numeric(19, 2),
    ADD COLUMN recovery_expected_position_count integer,
    ADD COLUMN recovery_reason varchar(500),
    ADD COLUMN recovery_requested_at timestamptz;

ALTER TABLE livesklad_webhook_receipts
    ADD CONSTRAINT livesklad_webhook_recovery_fields_check
    CHECK (
        (
            recovery_requested_by IS NULL
            AND recovery_idempotency_key IS NULL
            AND recovery_expected_document_number IS NULL
            AND recovery_expected_net_amount IS NULL
            AND recovery_expected_position_count IS NULL
            AND recovery_reason IS NULL
            AND recovery_requested_at IS NULL
        )
        OR
        (
            recovery_requested_by IS NOT NULL
            AND source_document_id IS NOT NULL
            AND recovery_idempotency_key IS NOT NULL
            AND recovery_expected_document_number IS NOT NULL
            AND recovery_expected_net_amount > 0
            AND recovery_expected_position_count > 0
            AND recovery_reason IS NOT NULL
            AND recovery_requested_at IS NOT NULL
            AND webhook_kind = 'SALE_RETURN'
            AND action_name = 'manualRecovery'
        )
    );

CREATE UNIQUE INDEX ux_livesklad_webhook_recovery_idempotency
    ON livesklad_webhook_receipts (
        recovery_requested_by,
        recovery_idempotency_key
    )
    WHERE recovery_requested_by IS NOT NULL;

CREATE UNIQUE INDEX ux_livesklad_webhook_recovery_external_id
    ON livesklad_webhook_receipts (source_document_id)
    WHERE recovery_requested_by IS NOT NULL;

COMMENT ON COLUMN livesklad_webhook_receipts.recovery_requested_by IS
    'Administrator who requested a validated historical return recovery.';

COMMENT ON COLUMN livesklad_webhook_receipts.recovery_expected_net_amount IS
    'Expected return net amount verified before any financial facts are changed.';
