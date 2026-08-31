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
implementation_sources:
  - backend/src/main/java/com/storeanalytics/common/config/SecurityConfig.java
  - backend/src/main/resources/application-prod.yml
  - deploy/Caddyfile
  - deploy/compose.production.yml
verification_sources:
  - backend/src/test/java/com/storeanalytics/auth/SecurityHardeningIntegrationTest.java
  - scripts/tests/security-hardening-test.sh
runtime_evidence: []
required_reviewers:
  - security-privacy
  - operations
review_triggers:
  - security-control-change
  - deployment-change
  - authentication-change
  - external-ingress-change
supersedes: []
superseded_by: null
---

# Базовый security-контракт

## Назначение и границы

Контракт перечисляет реализованные защитные границы. Он не является certification, pentest или
доказательством фактической настройки production host/cloud account.

## Действующий контракт

- Внешний HTTP завершается Caddy с HSTS, CSP, frame denial, nosniff, ограничением body и удалением
  server header; наружу проксируются только `/api/*`, `/livez` и `/readyz`, а `/actuator/*`
  принудительно отвечает `404`.
- Backend использует explicit CORS origins, session authentication, CSRF для state-changing
  browser requests, deny-by-default authorization и store-scoped проверки.
- Containers запускаются read-only, с `no-new-privileges`, tmpfs, ограниченными сетями и
  CPU/memory/PID budgets. Все capabilities сначала удаляются; web получает `NET_BIND_SERVICE`, а
  backend entrypoint — только capabilities, нужные для копирования secrets и перехода к user `app`.
- PostgreSQL соединения используют TLS `verify-full`; runtime, migrator и backup roles разделены.
- Provider/webhook inputs ограничены protocol, host, body size, authentication и idempotency
  controls соответствующего integration boundary.
- Secrets поступают через config-tree files, не через image или обычные environment values.

## Инварианты

- Новый endpoint закрыт, пока authorization rule не добавлен явно.
- Runtime role не получает migration privileges; backup role остаётся read-only.
- Secret, cookie, password, token и raw personal payload не логируются.
- Repository control не доказывает firewall, MFA облака, host patching, object-storage policy или
  alert routing.

## Подтверждённые ограничения

- Application MFA не реализована.
- Session registry process-local; разрешена одна API replica до shared registry.
- Supply-chain подпись/provenance, SBOM и vulnerability gate не enforced полностью.
- Runtime подключение monitoring, off-host logs и backup restore не доказано.

## Проверка

Integration tests проверяют auth/CSRF/headers/access; shell test — secret handling, unsafe URL и
backup artifacts. Host/cloud controls подтверждаются отдельным sanitized evidence.

## Триггеры пересмотра

Изменение ingress, auth, roles, container/network topology, database privileges, provider boundary
или security exception требует обновления baseline и risk register.
