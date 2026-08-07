ALTER TABLE notification_deliveries
    ADD COLUMN delivery_kind text NOT NULL DEFAULT 'NOTIFICATION'
        CHECK (delivery_kind IN ('NOTIFICATION', 'LINK_CONFIRMATION'));

ALTER TABLE notification_deliveries
    ALTER COLUMN event_id DROP NOT NULL;

ALTER TABLE notification_deliveries
    ADD CONSTRAINT ck_notification_deliveries_kind_shape CHECK (
        (delivery_kind = 'NOTIFICATION' AND event_id IS NOT NULL)
        OR
        (delivery_kind = 'LINK_CONFIRMATION' AND event_id IS NULL)
    );

CREATE UNIQUE INDEX ux_notification_deliveries_link_confirmation
    ON notification_deliveries (subscription_id, delivery_kind)
    WHERE delivery_kind = 'LINK_CONFIRMATION';

CREATE OR REPLACE FUNCTION validate_notification_delivery_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    subscription_user_id uuid;
    subscription_status text;
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
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN notification_deliveries.delivery_kind IS
    'Separates business notifications from lifecycle service messages.';
