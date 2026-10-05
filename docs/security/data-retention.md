---
doc_schema: 1
doc_type: current
status: current
owner: security
audience:
  - developer
  - operator
last_verified: 2026-10-05
requirement_sources:
  - docs/archive/legacy-contracts/data-retention.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotWriter.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogCompatibilityService.java
  - backend/src/main/resources/db/migration/V81__store_catalog_compatibility_confirmations.sql
  - backend/src/main/java/com/storeanalytics/common/config/DataRetentionProperties.java
  - backend/src/main/java/com/storeanalytics/maintenance
  - backend/src/main/java/com/storeanalytics/audit/service/AuditRetentionPolicy.java
  - backend/src/main/resources/db/migration/V12__add_data_retention.sql
  - backend/src/main/resources/db/migration/V94__preserve_weekly_ai_response_receipts.sql
  - backend/src/main/resources/application.yml
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogCompatibilityPersistenceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/config/DataRetentionPropertiesTest.java
  - backend/src/test/java/com/storeanalytics/maintenance
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiCompletionServiceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/audit/service/AuditRetentionPolicyTest.java
runtime_evidence: []
required_reviewers:
  - security-privacy
  - operations
  - backend
review_triggers:
  - retention-change
  - data-model-change
  - privacy-change
  - backup-change
supersedes: []
superseded_by: null
---

# Хранение и удаление данных

## Назначение и границы

Документ фиксирует технический retention engine. Он не является юридическим сроком хранения и не
разрешает deletion без customer approval, backup checkpoint и свежего restore evidence.

## Действующий контракт

- Scheduler работает только в WORKER/COMBINED role и сериализуется advisory lock.
- При `deletion-enabled=false` выполняется dry-run: считаются candidates, но данные не удаляются.
- Delete mode требует непустые approval/backup references и restore timestamp не старше configured
  maximum; future/stale timestamp блокирует run.
- Engine очищает ограниченные technical/provenance tables, агрегирует inventory history и соблюдает
  batch sizes. Financial facts и finalized report snapshots не являются общей целью purge.
- Audit entries имеют retention classes; financial entries и active holds защищены database rules.
- Каждый lock-owning run создаёт persistent audit только с counts/references, без raw payload.

## Инварианты

- Dry-run counts должны быть изучены до включения deletion.
- Restore timestamp/reference — guard конфигурации, а не доказательство существования approval,
  backup или успешного drill.
- Daily inventory retention должна превышать максимальный backfill horizon.
- Retention не заменяет backup, archival или legal hold process.

## Расхождения и открытые решения

- Нет обязательной машинной проверки, что approval/backup references разрешаются в immutable
  evidence; строка может быть формально непустой.
- Для LiveSklad webhook inbox, AI/Telegram payload/delivery tables не зафиксирован полный retention
  contract; бессрочное хранение остаётся privacy risk до отдельной реализации.
- Runtime dry-run history и customer legal basis не доказаны репозиторием.

## Проверка

Properties, repository/migration/service tests проверяют fail-closed configuration, cutoffs,
batches, holds и dry-run. Production deletion остаётся draft-процедурой до restore drill и
разовой авторизации exact target.

## Триггеры пересмотра

Новая таблица с payload/PII, изменение сроков, backfill horizon, deletion target, hold semantics,
backup или legal requirement требует совместного backend/security review.

## История атрибуции гарантий

`warranty_attach_decisions` и `warranty_attach_allocations` неизменяемы: исправление добавляет
ревизию. Автоматическое удаление не включено; срок хранения ограничен необходимостью
воспроизведения аналитики и требует отдельного утверждённого retention-процесса, а не удаления
вместе с raw. Событие WARRANTY_ATTACH_DECIDED отнесено к FINANCIAL audit retention без
автоматического удаления. `attach_source_employee_external_id` переживает raw retention и не
содержит имён/контактов. `attach_snapshot_checks` — производная отметка проверки, удаляемая
с соответствующим snapshot; `attach_attribution_changes` — один служебный маркер на магазин.

## История подтверждений совместимости каталога

`catalog_compatibility_decisions` хранит ревизии, исходную идентичность/наблюдение карточки,
автора, основание и при переносе — ссылку на старое свидетельство. Обычные UPDATE/DELETE
запрещены; REVOKE закрывает действие решения, но не удаляет доказательства. Событие
CATALOG_COMPATIBILITY_DECIDED относится к FINANCIAL audit retention, без автоматического purge.
Таблица не включена в очистку технических raw-данных. Для этих данных нужен отдельно
согласованный срок и процесс хранения/удаления, позволяющий воспроизвести аналитику;
неизменяемость не является юридическим разрешением хранить персональные данные бессрочно.
В свободное основание нельзя помещать контакты, секреты или полные provider payloads.
Локальные тесты не подтверждают развёртывание или перенос реальных решений в production.

## Первоначальные shadow-снимки ролей продаж

`catalog_sale_role_snapshots` сохраняет классифицирующие поля факта, роль, версии и ссылки
на подтверждение/оригинал. Обычные UPDATE/DELETE запрещены; изменение факта выявляется
представлением CURRENT/STALE/DELETED, а не удалением свидетельства. Запись не включена
в технический purge и не является raw payload. Срок хранения/контролируемые ревизии
требуют отдельного согласованного процесса до подключения к официальным расчётам.
Soft-delete исходной строки исключает её, но не стирает снимок. Физическое удаление факта
с таким снимком блокируется FK; будущие purge-процедуры должны учитывать эту зависимость.

## Независимые receipts недельного ИИ

Локальный кандидат добавляет `weekly_review_ai_response_receipts`: bounded provider response,
результат валидации, billing metadata и hashes на одну attempt. UPDATE/DELETE запрещены,
FK сохраняет связь с immutable attempt. Таблица не включена в technical purge. Она не является
разрешением бессрочного хранения: существующий открытый retention contract AI payloads должен
охватить и её, с контролируемым сроком, archival/legal holds и воспроизводимостью учёта расходов.
Payloads недоступны через новые публичные API и не выводятся в logs/evidence. До production
активации нужны отдельные security/privacy review, проверка runtime grants и migration rehearsal;
локальные тесты не подтверждают серверное внедрение или legal basis хранения.
