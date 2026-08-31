---
doc_schema: 1
doc_type: archive
status: archived
owner: ai
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/ai/README.md"
original_content_sha256: 0cbc820d82e6e93a7d67335546882abbac53d96e76bedc0676d5b88bd54e3cbe
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# LLM Interpretation Publication

Статус на 2026-08-03: publication claim/lease/heartbeat, canonical content materialization,
immutable revision chain, атомарный `SUCCESS` transition и durable weekly notification event
реализованы и проверены на PostgreSQL 16.

## Поток

```text
WAITING_RETRY / PUBLISH + SUCCEEDED attempt
→ claim через FOR UPDATE SKIP LOCKED
→ RUNNING + bounded lease
→ canonicalize validated response и рассчитать SHA-256
→ одна PostgreSQL-транзакция:
   ├─ lock store publication stream
   ├─ определить следующую interpretation revision
   ├─ INSERT immutable llm_interpretation
   ├─ INSERT immutable WEEKLY_REPORT_READY/REVISED notification_event
   └─ job RUNNING/PUBLISH → SUCCESS
```

Внешних HTTP-вызовов внутри транзакции нет. При любой ошибке interpretation, event и job transition
откатываются вместе. После commit повторный claim невозможен: job terminal, а unique
`analysis_job_id` и `successful_attempt_id` дополнительно защищают от дублей.

## Provenance и content

Публикация принимает только attempt со статусом `SUCCEEDED`, принадлежащую тому же job. DB trigger
повторно проверяет цепочку `snapshot → job → attempt`, store/period и статус attempt.

Provider response canonicalize-ится с сортировкой object keys; arrays не переставляются, потому что
их порядок является частью LLM content. `content_hash` считается по UTF-8 canonical JSON.
`validated_at` берётся из terminal timestamp attempt, `published_at` задаёт backend.

Raw response body не попадает в notification event и пользовательские API. Опубликованный
`content_payload` защищён trigger от UPDATE/DELETE.

## Revision и supersession

Store row блокируется до определения revision, поэтому параллельные публикации одного магазина не
получат одинаковый номер. Для конкретного store/type/period:

- первая публикация получает revision один и не имеет `supersedes_interpretation_id`;
- следующая получает `previous.revision + 1` и ссылается на предыдущую строку;
- snapshot revision и interpretation revision независимы;
- initial, snapshot revision, manual regeneration и model change сохраняются как
  `publication_reason_code`.

DB trigger проверяет непрерывную supersession chain. Dashboard позже выбирает максимальную revision,
не используя mutable `is_current`.

## Notification event как transactional outbox

В той же транзакции создаётся immutable manager event:

- `WEEKLY_REPORT_READY` для первой revision;
- `WEEKLY_REPORT_REVISED` для последующих;
- payload содержит только backend-owned IDs, period и interpretation revision;
- deterministic deduplication key включает interpretation ID и policy version;
- payload имеет собственный SHA-256 и TTL 24 часа.

`notification_events` служит durable transactional outbox. Реализованный fanout worker формирует
`notification_deliveries` для активных подписок и настроек идемпотентно по unique
`event/channel/subscription` и фиксирует terminal receipt, включая отсутствие получателей. Это не
позволяет Telegram API или отсутствие подписчиков блокировать готовность отчёта в dashboard и не
теряет событие между двумя транзакциями. Контракт описан в
[telegram-notification-fanout.md](telegram-notification-fanout.md); Telegram-флаги пока выключены.

## Crash recovery

Publication не имеет внешнего side effect. Если worker падает до commit, lease recovery возвращает
job в `WAITING_RETRY/PUBLISH` с той же `SUCCEEDED` attempt. Если commit завершился, job уже `SUCCESS`
и больше не выбирается. Это обеспечивает exactly-once database publication без распределённой
транзакции.

## Наблюдаемость

- `storeanalytics.interpretation.llm.publications` — успешные публикации;
- `storeanalytics.interpretation.llm.publication.duration` — histogram времени транзакции;
- общий jobs gauge отражает terminal `success`;
- expired lease и deadline используют общие LLM operational signals.

Логи worker не содержат content, prompt, response body или event payload.

## Rollout-граница

`INTERPRETATION_GENERATION_WORKER_ENABLED=false` сохраняется до завершения:

1. Telegram linking/webhook и delivery worker;
2. staging bot contract call, fault injection и Alertmanager verification.
