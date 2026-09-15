ALTER TABLE livesklad_webhook_receipts
    DROP CONSTRAINT livesklad_webhook_recovery_fields_check;

ALTER TABLE livesklad_webhook_receipts
    ADD COLUMN recovery_mode varchar(32),
    ADD COLUMN recovery_expected_current_employee_external_id varchar(24),
    ADD COLUMN recovery_expected_original_sale_external_id varchar(24),
    ADD COLUMN recovery_expected_original_employee_external_id varchar(24),
    ADD COLUMN recovery_expected_original_links jsonb;

UPDATE livesklad_webhook_receipts
SET recovery_mode = 'MISSING_RETURN'
WHERE recovery_requested_by IS NOT NULL;

DROP INDEX ux_livesklad_webhook_recovery_external_id;

CREATE UNIQUE INDEX ux_livesklad_webhook_recovery_external_id_mode
    ON livesklad_webhook_receipts (source_document_id, recovery_mode)
    WHERE recovery_requested_by IS NOT NULL;

ALTER TABLE livesklad_webhook_receipts
    ADD CONSTRAINT livesklad_webhook_recovery_fields_check
    CHECK (
        (
            recovery_requested_by IS NULL
            AND recovery_idempotency_key IS NULL
            AND recovery_expected_document_number IS NULL
            AND recovery_expected_net_amount IS NULL
            AND recovery_expected_position_count IS NULL
            AND recovery_mode IS NULL
            AND recovery_expected_current_employee_external_id IS NULL
            AND recovery_expected_original_sale_external_id IS NULL
            AND recovery_expected_original_employee_external_id IS NULL
            AND recovery_expected_original_links IS NULL
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
            AND recovery_mode IN (
                'MISSING_RETURN',
                'EXISTING_ORPHAN_RELINK'
            )
            AND recovery_reason IS NOT NULL
            AND recovery_requested_at IS NOT NULL
            AND webhook_kind = 'SALE_RETURN'
            AND action_name = 'manualRecovery'
            AND (
                (
                    recovery_mode = 'MISSING_RETURN'
                    AND recovery_expected_current_employee_external_id IS NULL
                    AND recovery_expected_original_sale_external_id IS NULL
                    AND recovery_expected_original_employee_external_id IS NULL
                    AND recovery_expected_original_links IS NULL
                )
                OR
                (
                    recovery_mode = 'EXISTING_ORPHAN_RELINK'
                    AND recovery_expected_original_sale_external_id IS NOT NULL
                    AND recovery_expected_original_employee_external_id IS NOT NULL
                    AND CASE
                        WHEN jsonb_typeof(
                                recovery_expected_original_links
                        ) = 'array'
                        THEN jsonb_array_length(
                                recovery_expected_original_links
                        ) = recovery_expected_position_count
                        ELSE false
                    END
                )
            )
        )
    );

COMMENT ON COLUMN livesklad_webhook_receipts.recovery_mode IS
    'MISSING_RETURN or guarded EXISTING_ORPHAN_RELINK processing mode.';

COMMENT ON COLUMN livesklad_webhook_receipts.recovery_expected_current_employee_external_id IS
    'Expected employee currently stored on an existing orphan return; NULL asserts that no employee is stored.';

COMMENT ON COLUMN livesklad_webhook_receipts.recovery_expected_original_sale_external_id IS
    'Expected LiveSklad parent sale ID for an existing orphan relink.';

COMMENT ON COLUMN livesklad_webhook_receipts.recovery_expected_original_employee_external_id IS
    'Expected employee ID of the parent sale after an existing orphan relink.';

COMMENT ON COLUMN livesklad_webhook_receipts.recovery_expected_original_links IS
    'Exact expected return-to-sale position links and immutable metric values.';
