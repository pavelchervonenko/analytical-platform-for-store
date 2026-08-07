ALTER TABLE notification_deliveries
    ADD COLUMN manual_resend_of uuid REFERENCES notification_deliveries(id),
    ADD COLUMN requested_by uuid REFERENCES app_users(id),
    ADD COLUMN resend_reason varchar(500),
    ADD CONSTRAINT ck_notification_deliveries_manual_resend_shape CHECK (
        (
            manual_resend_of IS NULL
            AND requested_by IS NULL
            AND resend_reason IS NULL
        ) OR (
            manual_resend_of IS NOT NULL
            AND requested_by IS NOT NULL
            AND resend_reason IS NOT NULL
            AND btrim(resend_reason) <> ''
            AND delivery_kind = 'NOTIFICATION'
        )
    );

ALTER TABLE notification_deliveries
    DROP CONSTRAINT notification_deliveries_event_id_channel_subscription_id_key;

CREATE UNIQUE INDEX ux_notification_deliveries_original_event_subscription
    ON notification_deliveries (event_id, channel, subscription_id)
    WHERE manual_resend_of IS NULL AND event_id IS NOT NULL;

CREATE INDEX ix_notification_deliveries_manual_resend
    ON notification_deliveries (manual_resend_of, created_at DESC)
    WHERE manual_resend_of IS NOT NULL;

CREATE OR REPLACE FUNCTION validate_notification_delivery_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    subscription_user_id uuid;
    subscription_status text;
    source notification_deliveries%ROWTYPE;
    actor_role text;
    actor_active boolean;
BEGIN
    SELECT user_id, status
    INTO subscription_user_id, subscription_status
    FROM telegram_subscriptions
    WHERE id = NEW.subscription_id;

    IF subscription_user_id IS DISTINCT FROM NEW.recipient_user_id THEN
        RAISE EXCEPTION 'Telegram delivery subscription does not belong to recipient';
    END IF;

    IF NEW.delivery_kind = 'NOTIFICATION'
            AND subscription_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Notification delivery subscription is not active for recipient';
    END IF;

    IF NEW.delivery_kind = 'LINK_CONFIRMATION'
            AND subscription_status IS DISTINCT FROM 'PENDING_CONFIRMATION' THEN
        RAISE EXCEPTION 'Link confirmation delivery subscription is not pending';
    END IF;

    IF NEW.manual_resend_of IS NOT NULL THEN
        SELECT * INTO source
        FROM notification_deliveries
        WHERE id = NEW.manual_resend_of;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Manual resend source delivery does not exist';
        END IF;

        IF source.delivery_kind <> 'NOTIFICATION'
                OR source.status NOT IN ('PERMANENT_FAILED', 'UNKNOWN_OUTCOME') THEN
            RAISE EXCEPTION 'Manual resend source is not an eligible terminal notification';
        END IF;

        IF source.expires_at <= CURRENT_TIMESTAMP THEN
            RAISE EXCEPTION 'Manual resend source notification has expired';
        END IF;

        SELECT role, is_active INTO actor_role, actor_active
        FROM app_users
        WHERE id = NEW.requested_by;

        IF actor_role IS DISTINCT FROM 'ADMIN' OR actor_active IS DISTINCT FROM true THEN
            RAISE EXCEPTION 'Manual resend requester is not an active administrator';
        END IF;

        IF NEW.status <> 'PENDING'
                OR NEW.attempt_count <> 0
                OR NEW.event_id IS DISTINCT FROM source.event_id
                OR NEW.recipient_user_id IS DISTINCT FROM source.recipient_user_id
                OR NEW.subscription_id IS DISTINCT FROM source.subscription_id
                OR NEW.channel IS DISTINCT FROM source.channel
                OR NEW.delivery_kind IS DISTINCT FROM source.delivery_kind
                OR NEW.render_version IS DISTINCT FROM source.render_version
                OR NEW.rendered_text IS DISTINCT FROM source.rendered_text
                OR NEW.rendered_markup IS DISTINCT FROM source.rendered_markup
                OR NEW.content_hash IS DISTINCT FROM source.content_hash
                OR NEW.expires_at IS DISTINCT FROM source.expires_at
                OR NEW.max_attempts IS DISTINCT FROM source.max_attempts THEN
            RAISE EXCEPTION 'Manual resend must preserve immutable source delivery content';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN notification_deliveries.manual_resend_of IS
    'Immediate source delivery for an explicit, audited manual resend.';
COMMENT ON COLUMN notification_deliveries.requested_by IS
    'Active administrator who explicitly accepted duplicate-delivery risk.';
COMMENT ON COLUMN notification_deliveries.resend_reason IS
    'Bounded operator reason; message content and Telegram identifiers are never copied here.';
