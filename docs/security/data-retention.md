---
doc_schema: 1
doc_type: current
status: current
owner: security
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/data-retention.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/common/config/DataRetentionProperties.java
  - backend/src/main/java/com/storeanalytics/maintenance
  - backend/src/main/java/com/storeanalytics/audit/service/AuditRetentionPolicy.java
  - backend/src/main/resources/db/migration/V12__add_data_retention.sql
  - backend/src/main/resources/application.yml
verification_sources:
  - backend/src/test/java/com/storeanalytics/common/config/DataRetentionPropertiesTest.java
  - backend/src/test/java/com/storeanalytics/maintenance
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
