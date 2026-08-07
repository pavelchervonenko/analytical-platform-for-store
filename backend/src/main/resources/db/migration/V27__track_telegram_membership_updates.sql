ALTER TABLE telegram_subscriptions
    ADD COLUMN last_membership_update_id bigint,
    ADD COLUMN last_membership_update_at timestamptz;

ALTER TABLE telegram_subscriptions
    ADD CONSTRAINT ck_telegram_subscriptions_membership_update_id
        CHECK (
            (last_membership_update_id IS NULL)
                = (last_membership_update_at IS NULL)
            AND (
                last_membership_update_id IS NULL
                OR last_membership_update_id >= 0
            )
        );

-- A blocked destination still belongs to the confirmed dashboard user. Keeping
-- it reserved prevents a second user from linking the same Telegram identity
-- while the bot is blocked and makes a later unblock transition unambiguous.
DROP INDEX ux_telegram_subscriptions_user_active;
DROP INDEX ux_telegram_subscriptions_telegram_user_active;
DROP INDEX ux_telegram_subscriptions_chat_active;

CREATE UNIQUE INDEX ux_telegram_subscriptions_user_active
    ON telegram_subscriptions (bot_code, user_id)
    WHERE status IN ('PENDING_CONFIRMATION', 'ACTIVE', 'BOT_BLOCKED');

CREATE UNIQUE INDEX ux_telegram_subscriptions_telegram_user_active
    ON telegram_subscriptions (bot_code, telegram_user_id)
    WHERE status IN ('PENDING_CONFIRMATION', 'ACTIVE', 'BOT_BLOCKED');

CREATE UNIQUE INDEX ux_telegram_subscriptions_chat_active
    ON telegram_subscriptions (bot_code, telegram_chat_id)
    WHERE status IN ('PENDING_CONFIRMATION', 'ACTIVE', 'BOT_BLOCKED');
