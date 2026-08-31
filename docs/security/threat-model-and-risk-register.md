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
  - backend/src/main/java/com/storeanalytics/common/config/SecurityConfig.java
  - deploy/compose.production.yml
  - deploy/bin
  - docs/security/baseline.md
verification_sources:
  - backend/src/test/java/com/storeanalytics/auth/SecurityHardeningIntegrationTest.java
  - scripts/tests/security-hardening-test.sh
  - scripts/tests/deploy-release-safety-test.sh
runtime_evidence: []
required_reviewers:
  - security-privacy
  - operations
  - backend
review_triggers:
  - threat-model-change
  - architecture-change
  - external-integration-change
  - incident
supersedes: []
superseded_by: null
---

# Модель угроз и реестр рисков

## Назначение и границы

Реестр фиксирует существенные угрозы и остаточные риски по статическому аудиту. Severity не
подтверждает факт эксплуатации. Runtime/cloud risks требуют отдельного evidence и владельца.

## Активы и trust boundaries

- Финансовые факты, сотрудники, payroll, отчёты и audit в managed PostgreSQL.
- Application sessions, admin/store access и emergency credentials.
- LiveSklad, YandexGPT и Telegram credentials/payloads.
- Release images, migration state, backup objects и decryption key.
- Boundaries: public Caddy, API, worker egress, migration role, database, object storage, CI/registry.

## Реестр

| ID | Риск | Уровень | Реализованная защита | Остаток/условие закрытия |
|---|---|---|---|---|
| OPS-01 | Migration failure оставляет `MIGRATION_IN_PROGRESS` | critical | Deploy останавливается | Нужен live Flyway recovery tool и rehearsed forward-fix |
| OPS-02 | Backup нельзя восстановить | critical | Encrypted dump, manifest, upload size | Isolated restore, checksum download, RPO/RTO evidence |
| SEC-01 | Захват single-factor admin | high | Strong password, throttle, sessions, audit | Application MFA и customer recovery governance |
| SEC-02 | Компрометация secrets | high | File delivery, permissions, redaction | Vault/KMS, rotation evidence, previous-secret mounts |
| SEC-03 | Supply-chain substitution | high | Pinned actions, locks, digest publish | SBOM, scan, signature/provenance и server verify |
| OPS-03 | Невидимый outage/backlog | high | Health, metrics, rule artifacts, host probe | Runtime wiring, stale-backup/queue alerts, delivery test |
| SEC-04 | PII/payload хранится бессрочно | high | Bounded audit/telemetry, partial retention | Retention для webhook/AI/Telegram и privacy tests |
| OPS-04 | Несогласованные sessions при scale-out | high | Single API topology | Shared session registry и multi-replica tests |
| SEC-05 | Emergency access недоступен/злоупотреблён | high | Break-glass audit/alerts | Customer custody и periodic rehearsal |
| OPS-05 | ACL repair направлен не в ту БД | high | TLS и transactional grants | Release-derived target, exact-target preflight |
| OPS-06 | Migration несовместима с работающим старым runtime | high | Candidate migration compatibility checks | Проверка current-runtime→target-schema или контролируемая остановка writers до Flyway |

## Инварианты принятия риска

- Critical/high risk не закрывается только документом или unit test.
- Production write требует exact target, независимую авторизацию и соответствующий runbook gate.
- Неизвестное runtime-состояние обозначается `not evidenced`, а не предполагается безопасным.
- Risk acceptance хранится как датированное decision/evidence с владельцем и сроком пересмотра.

## Проверка

Реестр сверяется с кодом, tests, incident/release evidence и текущим project-state. Закрытие строки
требует отдельного evidence, а не удаления формулировки после реализации кода.

## Триггеры пересмотра

Incident, новая integration, migration/backup topology, identity model, data class или deployment
pipeline требует threat review.
