package com.storeanalytics.integration.livesklad.webhook;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class LiveSkladWebhookStore {

    private final JdbcTemplate jdbcTemplate;

    LiveSkladWebhookStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void record(LiveSkladWebhookReceipt receipt) {
        jdbcTemplate.update(
                """
                INSERT INTO livesklad_webhook_receipts (
                    webhook_kind,
                    event_id,
                    action_id,
                    action_group_id,
                    action_name,
                    payload,
                    payload_sha256,
                    last_payload_sha256,
                    first_received_at,
                    last_received_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (webhook_kind, event_id) DO UPDATE
                SET delivery_count =
                        livesklad_webhook_receipts.delivery_count + 1,
                    last_received_at = GREATEST(
                        livesklad_webhook_receipts.last_received_at,
                        EXCLUDED.last_received_at
                    ),
                    last_payload_sha256 = EXCLUDED.last_payload_sha256,
                    payload_mismatch =
                        livesklad_webhook_receipts.payload_mismatch
                        OR livesklad_webhook_receipts.payload_sha256
                            <> EXCLUDED.payload_sha256
                """,
                receipt.kind().name(),
                receipt.eventId(),
                receipt.actionId(),
                receipt.actionGroupId(),
                receipt.actionName(),
                receipt.payload(),
                receipt.payloadSha256(),
                receipt.payloadSha256(),
                Timestamp.from(receipt.receivedAt()),
                Timestamp.from(receipt.receivedAt())
        );
    }
}
