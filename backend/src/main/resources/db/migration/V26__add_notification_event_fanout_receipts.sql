CREATE TABLE notification_event_fanout_receipts (
    event_id uuid PRIMARY KEY REFERENCES notification_events(id),
    outcome text NOT NULL
        CHECK (outcome IN (
            'DELIVERIES_CREATED',
            'NO_RECIPIENTS',
            'EVENT_EXPIRED'
        )),
    recipient_count integer NOT NULL CHECK (recipient_count >= 0),
    delivery_count integer NOT NULL CHECK (delivery_count >= 0),
    processed_at timestamptz NOT NULL DEFAULT now(),
    CHECK (delivery_count <= recipient_count),
    CHECK (
        (outcome = 'DELIVERIES_CREATED' AND delivery_count > 0)
        OR
        (outcome IN ('NO_RECIPIENTS', 'EVENT_EXPIRED')
            AND recipient_count = 0 AND delivery_count = 0)
    )
);

CREATE INDEX ix_notification_event_fanout_receipts_processed
    ON notification_event_fanout_receipts (processed_at);

CREATE FUNCTION prevent_notification_fanout_receipt_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Notification fanout receipts are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_notification_event_fanout_receipts_immutable
    BEFORE UPDATE OR DELETE ON notification_event_fanout_receipts
    FOR EACH ROW EXECUTE FUNCTION prevent_notification_fanout_receipt_change();

COMMENT ON TABLE notification_event_fanout_receipts IS
    'Terminal idempotency receipt for projecting one immutable event into deliveries.';
