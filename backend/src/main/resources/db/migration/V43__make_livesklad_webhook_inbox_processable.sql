DO $$
DECLARE
    original_constraint text;
BEGIN
    SELECT constraint_entry.conname
    INTO original_constraint
    FROM pg_constraint constraint_entry
    WHERE constraint_entry.conrelid = 'sales_documents'::regclass
      AND constraint_entry.contype = 'c'
      AND pg_get_constraintdef(constraint_entry.oid)
          ILIKE '%document_kind%'
      AND pg_get_constraintdef(constraint_entry.oid)
          ILIKE '%original_document_id%'
    LIMIT 1;

    IF original_constraint IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE sales_documents DROP CONSTRAINT %I',
            original_constraint
        );
    END IF;
END
$$;

ALTER TABLE sales_documents
    ADD CONSTRAINT sales_documents_original_kind_check
    CHECK (document_kind <> 'SALE' OR original_document_id IS NULL);

ALTER TABLE livesklad_webhook_receipts
    ADD COLUMN IF NOT EXISTS source_document_id varchar(256),
    ADD COLUMN IF NOT EXISTS terminal_failure boolean NOT NULL DEFAULT false;

DROP INDEX IF EXISTS ix_livesklad_webhook_receipts_processing;

CREATE INDEX IF NOT EXISTS ix_livesklad_webhook_receipts_processing
    ON livesklad_webhook_receipts (
        processing_status,
        available_at,
        first_received_at
    )
    WHERE processing_status = 'RECEIVED'
       OR (
           processing_status = 'FAILED'
           AND terminal_failure = false
       );

CREATE INDEX IF NOT EXISTS ix_livesklad_webhook_receipts_lease
    ON livesklad_webhook_receipts (lease_until)
    WHERE processing_status = 'PROCESSING';

COMMENT ON COLUMN livesklad_webhook_receipts.source_document_id IS
    'LiveSklad document identifier extracted from payload.data.id.';

COMMENT ON COLUMN livesklad_webhook_receipts.terminal_failure IS
    'True when automatic retries are unsafe or exhausted.';
