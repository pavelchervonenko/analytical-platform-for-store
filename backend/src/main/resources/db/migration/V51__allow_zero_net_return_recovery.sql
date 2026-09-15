ALTER TABLE livesklad_webhook_receipts
    DROP CONSTRAINT livesklad_webhook_recovery_fields_check;

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
            AND recovery_expected_net_amount >= 0
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

COMMENT ON COLUMN livesklad_webhook_receipts.recovery_expected_net_amount IS
    'Expected non-negative return net amount; zero-net item returns still require a positive position count and exact worker verification.';
