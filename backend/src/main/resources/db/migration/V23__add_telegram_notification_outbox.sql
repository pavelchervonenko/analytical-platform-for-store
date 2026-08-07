CREATE TABLE telegram_subscriptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_users(id),
    bot_code text NOT NULL,
    telegram_user_id bigint NOT NULL,
    telegram_chat_id bigint NOT NULL,
    chat_type text NOT NULL DEFAULT 'PRIVATE'
        CHECK (chat_type IN ('PRIVATE')),
    status text NOT NULL
        CHECK (status IN (
            'PENDING_CONFIRMATION',
            'ACTIVE',
            'BOT_BLOCKED',
            'REVOKED',
            'EXPIRED'
        )),
    delivery_timezone text NOT NULL DEFAULT 'Europe/Kaliningrad',
    quiet_hours_enabled boolean NOT NULL DEFAULT true,
    quiet_hours_start time NOT NULL DEFAULT '21:00:00',
    quiet_hours_end time NOT NULL DEFAULT '08:00:00',
    pending_expires_at timestamptz,
    confirmed_at timestamptz,
    last_inbound_at timestamptz,
    blocked_at timestamptz,
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (btrim(bot_code) <> ''),
    CHECK (btrim(delivery_timezone) <> ''),
    CHECK (
        status <> 'PENDING_CONFIRMATION'
        OR (pending_expires_at IS NOT NULL AND confirmed_at IS NULL)
    ),
    CHECK (status <> 'ACTIVE' OR confirmed_at IS NOT NULL),
    CHECK (status <> 'BOT_BLOCKED' OR blocked_at IS NOT NULL),
    CHECK (status <> 'REVOKED' OR revoked_at IS NOT NULL)
);

CREATE UNIQUE INDEX ux_telegram_subscriptions_user_active
    ON telegram_subscriptions (bot_code, user_id)
    WHERE status IN ('PENDING_CONFIRMATION', 'ACTIVE');

CREATE UNIQUE INDEX ux_telegram_subscriptions_telegram_user_active
    ON telegram_subscriptions (bot_code, telegram_user_id)
    WHERE status IN ('PENDING_CONFIRMATION', 'ACTIVE');

CREATE UNIQUE INDEX ux_telegram_subscriptions_chat_active
    ON telegram_subscriptions (bot_code, telegram_chat_id)
    WHERE status IN ('PENDING_CONFIRMATION', 'ACTIVE');

CREATE INDEX ix_telegram_subscriptions_status
    ON telegram_subscriptions (status, updated_at);

CREATE TRIGGER tr_telegram_subscriptions_updated_at
    BEFORE UPDATE ON telegram_subscriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE telegram_link_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_users(id),
    bot_code text NOT NULL,
    purpose text NOT NULL CHECK (purpose IN ('LINK', 'RELINK')),
    token_hash varchar(64) NOT NULL UNIQUE
        CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    pending_subscription_id uuid REFERENCES telegram_subscriptions(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (btrim(bot_code) <> ''),
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);

CREATE UNIQUE INDEX ux_telegram_link_tokens_one_open
    ON telegram_link_tokens (user_id, bot_code)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX ix_telegram_link_tokens_expiry
    ON telegram_link_tokens (expires_at)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE notification_preferences (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    channel text NOT NULL CHECK (channel IN ('TELEGRAM')),
    event_type text NOT NULL,
    enabled boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, store_id, channel, event_type),
    CHECK (btrim(event_type) <> '')
);

CREATE INDEX ix_notification_preferences_store
    ON notification_preferences (store_id, event_type);

CREATE TRIGGER tr_notification_preferences_updated_at
    BEFORE UPDATE ON notification_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE telegram_update_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_code text NOT NULL,
    update_id bigint NOT NULL,
    update_type text NOT NULL,
    payload_hash varchar(64) NOT NULL
        CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    outcome text NOT NULL
        CHECK (outcome IN ('PROCESSED', 'IGNORED', 'REJECTED')),
    processed_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (bot_code, update_id),
    CHECK (btrim(bot_code) <> ''),
    CHECK (btrim(update_type) <> '')
);

CREATE INDEX ix_telegram_update_receipts_retention
    ON telegram_update_receipts (processed_at);

CREATE TABLE notification_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid REFERENCES stores(id),
    event_type text NOT NULL
        CHECK (event_type IN (
            'WEEKLY_REPORT_READY',
            'WEEKLY_REPORT_REVISED',
            'DAILY_STORE_PULSE',
            'STORE_ACHIEVEMENT',
            'OPERATOR_LLM_FAILED',
            'OPERATOR_DELIVERY_FAILED',
            'OPERATOR_SYNC_FAILED',
            'OPERATOR_DATA_QUALITY'
        )),
    audience text NOT NULL CHECK (audience IN ('MANAGER', 'OPERATOR')),
    interpretation_id uuid REFERENCES llm_interpretations(id),
    snapshot_id uuid REFERENCES analytics_snapshots(id),
    deduplication_key text NOT NULL UNIQUE,
    notification_policy_version text NOT NULL,
    priority text NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'CRITICAL')),
    event_payload jsonb NOT NULL
        CHECK (jsonb_typeof(event_payload) = 'object'),
    payload_hash varchar(64) NOT NULL
        CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    not_before timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (btrim(deduplication_key) <> ''),
    CHECK (btrim(notification_policy_version) <> ''),
    CHECK (expires_at IS NULL OR expires_at > not_before),
    CHECK (audience <> 'MANAGER' OR store_id IS NOT NULL),
    CHECK (
        event_type NOT IN ('WEEKLY_REPORT_READY', 'WEEKLY_REPORT_REVISED')
        OR (interpretation_id IS NOT NULL AND snapshot_id IS NOT NULL)
    )
);

CREATE INDEX ix_notification_events_store_created
    ON notification_events (store_id, created_at DESC);
CREATE INDEX ix_notification_events_expiry
    ON notification_events (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE FUNCTION prevent_notification_event_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Notification events are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_notification_events_immutable
    BEFORE UPDATE OR DELETE ON notification_events
    FOR EACH ROW EXECUTE FUNCTION prevent_notification_event_change();

CREATE TABLE notification_deliveries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL REFERENCES notification_events(id),
    channel text NOT NULL DEFAULT 'TELEGRAM'
        CHECK (channel IN ('TELEGRAM')),
    recipient_user_id uuid NOT NULL REFERENCES app_users(id),
    subscription_id uuid NOT NULL REFERENCES telegram_subscriptions(id),
    status text NOT NULL
        CHECK (status IN (
            'PENDING',
            'RUNNING',
            'WAITING_RETRY',
            'SENT',
            'PERMANENT_FAILED',
            'UNKNOWN_OUTCOME',
            'EXPIRED',
            'CANCELLED'
        )),
    render_version text NOT NULL,
    rendered_text text NOT NULL
        CHECK (
            btrim(rendered_text) <> ''
            AND octet_length(rendered_text) <= 16384
        ),
    rendered_markup jsonb CHECK (
        rendered_markup IS NULL OR jsonb_typeof(rendered_markup) = 'object'
    ),
    content_hash varchar(64) NOT NULL
        CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    scheduled_at timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
    lease_owner varchar(100),
    lease_until timestamptz,
    cancel_requested boolean NOT NULL DEFAULT false,
    provider_message_id text,
    sent_at timestamptz,
    error_code text,
    error_summary text,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (event_id, channel, subscription_id),
    CHECK (expires_at > scheduled_at),
    CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (
        (status = 'SENT')
        = (provider_message_id IS NOT NULL AND sent_at IS NOT NULL)
    )
);

CREATE INDEX ix_notification_deliveries_claim
    ON notification_deliveries (next_attempt_at, scheduled_at, created_at)
    WHERE status IN ('PENDING', 'WAITING_RETRY');

CREATE INDEX ix_notification_deliveries_expired_lease
    ON notification_deliveries (lease_until)
    WHERE status = 'RUNNING';

CREATE INDEX ix_notification_deliveries_recipient
    ON notification_deliveries (recipient_user_id, created_at DESC);

CREATE FUNCTION validate_notification_delivery_insert()
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

    IF subscription_user_id IS DISTINCT FROM NEW.recipient_user_id
            OR subscription_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Notification delivery subscription is not active for recipient';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_notification_deliveries_validate
    BEFORE INSERT ON notification_deliveries
    FOR EACH ROW EXECUTE FUNCTION validate_notification_delivery_insert();

CREATE TRIGGER tr_notification_deliveries_updated_at
    BEFORE UPDATE ON notification_deliveries
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE notification_delivery_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id uuid NOT NULL REFERENCES notification_deliveries(id),
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    status text NOT NULL
        CHECK (status IN (
            'STARTED',
            'SENT',
            'TRANSIENT_FAILED',
            'PERMANENT_FAILED',
            'UNKNOWN_OUTCOME',
            'CANCELLED'
        )),
    provider_message_id text,
    http_status integer CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    retry_after_at timestamptz,
    latency_ms bigint CHECK (latency_ms IS NULL OR latency_ms >= 0),
    error_code text,
    error_summary text,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (delivery_id, attempt_number),
    CHECK ((status = 'STARTED') = (finished_at IS NULL)),
    CHECK (finished_at IS NULL OR finished_at >= started_at),
    CHECK (status <> 'SENT' OR provider_message_id IS NOT NULL)
);

CREATE INDEX ix_notification_delivery_attempts_delivery
    ON notification_delivery_attempts (delivery_id, attempt_number DESC);

COMMENT ON TABLE telegram_subscriptions IS
    'Confirmed mapping between an app user and one private Telegram destination.';
COMMENT ON TABLE telegram_link_tokens IS
    'Single-use dashboard-to-Telegram link tokens; only token hashes are stored.';
COMMENT ON TABLE notification_events IS
    'Immutable business or operator notification event with deterministic deduplication.';
COMMENT ON TABLE notification_deliveries IS
    'Durable Telegram outbox row containing the exact text approved for delivery.';
COMMENT ON TABLE notification_delivery_attempts IS
    'One Telegram API call outcome without token, headers or provider response body.';
COMMENT ON TABLE telegram_update_receipts IS
    'Minimal Telegram webhook inbox for at-least-once update deduplication.';
