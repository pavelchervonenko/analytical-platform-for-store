---
doc_schema: 1
doc_type: current
status: current
owner: integrations
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/livesklad-webhook-receiver.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/integration/livesklad/webhook
  - backend/src/main/resources/db/migration/V39.1__add_livesklad_webhook_inbox.sql
  - backend/src/main/resources/db/migration/V42__add_livesklad_webhook_inbox.sql
  - backend/src/main/resources/db/migration/V43__make_livesklad_webhook_inbox_processable.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladWebhookSecurityIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladWebhookStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladSaleReturnWebhookWorkerTest.java
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladOrderReturnWebhookWorkerTest.java
runtime_evidence: []
required_reviewers:
  - integration
  - backend-data
  - security-privacy
review_triggers:
  - webhook-contract-change
  - webhook-secret-change
  - webhook-retention-change
supersedes:
  - docs/livesklad-webhook-receiver.md
superseded_by: null
---

# Webhook LiveSklad

## Ingress

Два hidden-from-OpenAPI provider endpoints принимают только JSON POST:

- `/api/integrations/livesklad/webhooks/sale-returns`;
- `/api/integrations/livesklad/webhooks/order-returns`.

Authentication — endpoint-specific secret в `X-Store-Analytics-Webhook-Token`; current и previous
secret сравниваются constant-time для controlled rotation. При выключенном receiver endpoint
возвращает 404. Body ограничен по declared и фактическому числу bytes.

URL verification принимает `X-LiveSklad-Verification` или bounded challenge из body и отвечает
тем же plain text. Event после durable persistence отвечает plain `OK`. Неуспешная auth,
validation или persistence не подтверждает событие как принятое.

## Envelope semantics

- `eventId` — identity доставки; dedupe key `(webhook_kind, event_id)`.
- `action.id`, `action.groupId` и `action.name` сохраняются как metadata, но `action.id` никогда не
  используется как ID документа.
- Оба worker читают scalar `data.id`. Sale-return трактует его как return document ID;
  order-return — как order ID.
- Первый canonical payload остаётся исходным; redelivery увеличивает `delivery_count`. Другой hash
  устанавливает `payload_mismatch` и terminal failure.

## Processing

SALE_RETURN и ORDER_RETURN имеют отдельные claim paths. Receipt проходит
`RECEIVED/FAILED → PROCESSING → PROCESSED` либо terminal `FAILED`. Claim использует lease и
`FOR UPDATE SKIP LOCKED`; expired lease становится retryable либо terminal после exhaustion.

Retryable: rate limit, transport, selected 404/409/5xx, source changed races и transient DB.
Invalid/missing `data.id`, rejected payload, mismatch и exhausted retry — terminal с stable code.
Targeted operation обновляет exact document/order под transaction lock и не выполняет period-wide
deletion.

Order-return contract `data.id → order detail` остаётся provider observation, требующим реального
sanitized canary evidence; code/tests доказывают наше ожидаемое поведение, а не provider guarantee.

## Защита данных и retention gap

Следует настраивать минимальный event payload без customer identity, phone, email, address,
passport, bank или других PII. Тем не менее текущая реализация сохраняет весь выбранный JSON в
`livesklad_webhook_receipts`. Отдельный field allowlist и retention period для inbox не найдены.
До их реализации доступ к payload должен быть строго operational, а документ не объявляет
бессрочное хранение допустимым.

Операторская canary/activation процедура находится в
[`../../../runbooks/livesklad-webhooks.md`](../../../runbooks/livesklad-webhooks.md). Этот контракт
не утверждает фактическое значение feature flags.
