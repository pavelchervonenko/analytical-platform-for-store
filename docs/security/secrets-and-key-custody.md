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
  - docs/archive/legacy-contracts/security-hardening.md
  - docs/archive/legacy-contracts/deployment-and-operations.md
implementation_sources:
  - deploy/compose.production.yml
  - deploy/env.production.example
  - deploy/backup.env.example
  - deploy/bin/preflight-release.sh
  - deploy/bin/release-safety.sh
  - deploy/bin/provision-release-secrets.sh
verification_sources:
  - scripts/tests/deploy-release-safety-test.sh
  - scripts/tests/security-hardening-test.sh
runtime_evidence: []
required_reviewers:
  - security-privacy
  - operations
review_triggers:
  - secret-change
  - provider-change
  - compose-change
  - rotation-change
supersedes: []
superseded_by: null
---

# Секреты и хранение ключей

## Назначение и границы

Контракт описывает delivery и validation secret files. Он не фиксирует сами значения, их текущий
age, владельца customer vault или факт успешной ротации.

## Действующий контракт

- Production secrets задаются абсолютными file paths и монтируются как Compose secrets/config tree.
  Secret values не должны находиться в release env, Git, image layers или command line.
- Preflight требует regular non-symlink, readable, non-empty root-owned files mode `0600`; webhook
  secrets дополнительно проверяются как 32–256 URL-safe characters.
- PostgreSQL runtime/migrator/backup, LiveSklad, webhook, Yandex, Telegram, telemetry, Prometheus и
  bootstrap credentials разделены по назначению.
- `provision-release-secrets.sh` создаёт только два LiveSklad webhook secrets атомарно и сохраняет
  уже существующие; он не является общим secret manager.
- Backup passphrase и object-storage credentials используют отдельный backup environment.

## Инварианты

- В evidence попадают только path basename, key ID, timestamp и результат проверки — не value.
- Ротация завершается доказанным отказом старого credential, а не только загрузкой нового.
- Backup decryption key не должен иметь единственную копию на application host.
- Компрометация одного provider credential не разрешает автоматически менять остальные.

## Подтверждённые ограничения

- Backend-код отдельных webhook boundaries может принимать current/previous secret, но production
  Compose не монтирует previous LiveSklad/Telegram secret. Dual-secret rollout сейчас не
  воспроизводим как заявленная универсальная процедура.
- Нет репозиторного vault/KMS, automatic rotation, expiry inventory или customer custody evidence.
- Base release preflight проверяет форму файла, но не provenance/age/revocation credential.

## Проверка

Shell tests проверяют mode/type/duplicate handling и отсутствие секретов в environment/argv для
охваченных путей. Фактическая ротация требует runbook и sanitized staging/production evidence.

## Триггеры пересмотра

Новый secret, previous-secret support, KMS/vault, provider rotation policy, Compose mount или key
custody model требует обновления документа.
