---
doc_schema: 1
doc_type: evidence
status: historical
owner: backend
audience:
  - developer
  - operations
snapshot_date: 2026-10-05
verdict: PASS
verdict_scope: Targeted durable provider-response receipts, lease fencing, budget and migration regressions; not production activation
verification_levels:
  - local
source_of_truth:
  - docs/maintenance/weekly-ai-production-automation-plan.md
  - docs/current/ai/weekly-review.md
  - docs/security/data-retention.md
required_reviewers:
  - backend
  - security-privacy
  - operations
---

# Независимые receipts недельного ИИ: локальная проверка

Локальный кандидат добавляет неизменяемую receipt на attempt: bounded response, hashes,
validation outcome/violations и billing metadata. Запись выполняется REQUIRES_NEW до перехода
публикации/retry, поэтому откат внешней транзакции не стирает сведения о платном ответе.
Завершённая attempt не изменяется; receipt может добавиться после UNKNOWN recovery.
Production, внешние провайдеры, платные вызовы и системные flags не затрагивались.

## Проверки

- Окончательный targeted run: 45 tests в 6 классах, 0 failures/errors/skips. Классы:
  WeeklyReviewAiCompletionServiceIntegrationTest (5), WeeklyReviewAiJobStoreIntegrationTest (20),
  WeeklyReviewAiBudgetReservationIntegrationTest (5), WeeklyReviewAiGenerationExecutionServiceTest (13),
  WeeklyReviewAiJobRunnerTest (1), ExpectedSchemaVersionTest (1). Checkstyle main/test PASS.
- Проверены потеря владельца, точная граница lease, recovery до/после response,
  отсутствие enrichment после потери права, сохранение известного расхода, одинаковый повтор
  записи без двойного расхода, unknown price с сохранением оценки и запрет дополнительного call
  при превышении технического лимита. Validator crash не теряет response и не даёт paid retry.
  Wrong request hash/attempt number и конфликт billing metadata отвергаются; UPDATE/DELETE
  receipt запрещены. Уже сохранённый legacy response/price нельзя переопределить новой receipt.
- Отдельный migration/security run: 44 tests в 34 классах, 0 failures/errors/skips,
  Checkstyle main/test PASS. Включены MigrationApplicationIntegrationTest, application schema
  metadata, SecurityHardeningIntegrationTest, database migration suites и least-privilege migrator.
  WeeklyReviewV48MigrationIntegrationTest проходит через предыдущий target с заполненными
  started/final attempts: outcomes сохраняются, новая receipt table пуста, final attempt
  по-прежнему защищена от изменения. Это synthetic локальная upgrade-проверка, не host rehearsal.
- Первый targeted pipeline: backend tests прошли, Checkstyle отверг helper с 8 параметрами.
  Сигнатура сокращена без подавления правила; последующие финальные gates проходят.
- Documentation unit tests (25), strict inventory/diff, host operator security и Gradle
  supply-chain (449 components/840 artifacts) проходят. Полный backend/frontend/CI gate
  окончательного объединённого кода этим результатом не заменяется.

## Self-review и оставшиеся границы

Receipt привязана к точной DB attempt/job/number/request/input identity. Идемпотентность сравнивает
hash response и metadata/validation, но не время повторного callback. Внешняя транзакция может
откатить attempt transition и enrichment, сохраняя внутреннюю receipt. Legacy attempt cost и
receipt cost не складываются; budget reservation сериализует обе таблицы. Проверка публикации
и retry требует живого владельца, deadline и точного attempt count; recovery после оплаченной
попытки остаётся terminal, даже когда пришёл поздний ответ.

Crash/недоступная БД до записи остаются UNKNOWN; отсутствие receipt не доказывает нулевой расход.
Нет spool/replay внешнего провайдера, автоматического возобновления terminal job или обхода cap.
Финальная гонка source freshness/publication, исторический состав, temporal attach и durable
backlog ещё требуют отдельных этапов. Новая схема требует проверки runtime grants, совместимости
старого executable и host migration rehearsal; rollback одним старым image не предполагается.
Privacy/retention review остаётся обязательным: неизменяемость не разрешает бессрочно хранить
AI payloads. Body не печатается в logs/evidence и не добавлен в публичный API. Frontend source
и интерфейс не менялись; visual review в этом пакете не выполнялся. Постоянный автоматический
режим и новый ИИ-разбор пока не опубликованы.
