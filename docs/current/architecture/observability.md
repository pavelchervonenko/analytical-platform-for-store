---
doc_schema: 1
doc_type: current
status: current
owner: operations
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/observability.md
implementation_sources:
  - backend/src/main/resources/application.yml
  - backend/src/main/java/com/storeanalytics/common/observability
  - backend/src/main/java/com/storeanalytics/common/config/SecurityConfig.java
  - deploy/bin/monitor-health.sh
  - deploy/systemd/store-analytics-health.service
  - deploy/systemd/store-analytics-health.timer
  - monitoring/prometheus
verification_sources:
  - backend/src/test/java/com/storeanalytics/common/observability
  - backend/src/test/java/com/storeanalytics/integration/livesklad/health/LiveSkladHealthIndicatorTest.java
  - scripts/tests/security-hardening-test.sh
runtime_evidence: []
required_reviewers:
  - operations
  - backend
review_triggers:
  - observability-change
  - alert-change
  - health-contract-change
  - deployment-change
supersedes: []
superseded_by: null
---

# Наблюдаемость

## Назначение и границы

Документ описывает сигналы, которые приложение умеет формировать. Наличие rule-файлов или endpoint
не означает, что production Prometheus, Alertmanager, off-host logs или конечный канал уведомлений
подключены.

## Действующий контракт

- `/livez` проверяет жизнеспособность процесса; `/readyz` включает readiness, в том числе точное
  совпадение последней успешной Flyway migration с версией, упакованной в image.
- Health details публично не раскрываются. LiveSklad availability является отдельным indicator и
  не превращает внешний provider outage в потерю liveness процесса.
- `/actuator/prometheus` использует отдельный stateless filter: без настроенного token отвечает
  `404`, при неверном Bearer token — `401`; сравнение выполняется constant-time.
- Release identity экспортирует ограниченные version/role/schema/release labels. Dynamic release
  truth хранится только в `docs/current/project-state.md` после runtime-проверки.
- API requests, background jobs, data quality, sync, payroll, retention, AI и delivery создают
  bounded metrics. Высококардинальные IDs не должны становиться labels.
- Structured security/business events проходят fail-closed envelope и используют псевдонимные
  references вместо raw identifiers.
- Host monitor проверяет только публичный `/readyz` и отправляет Telegram сообщение при изменении
  состояния. Он не проверяет очереди, backup age, БД отдельно или полноту бизнес-данных.

## Инварианты

- Секреты, payload, session IDs, email, chat IDs и внешние document IDs не являются metric labels.
- Readiness `UP` не доказывает корректность бизнес-агрегатов, backup или alert delivery.
- Rule-файл в `monitoring/prometheus` — configuration artifact, а не runtime evidence.
- Сбой observability exporter не должен открывать публичный management endpoint.

## Реализовано, но не доказано в runtime

| Слой | Реализовано | Не доказано |
|---|---|---|
| Health | Probes, schema readiness, provider indicator | Фактический scrape/availability history |
| Metrics | Micrometer Prometheus registry и authorization | Production Prometheus wiring и retention |
| Alerts | Набор Prometheus rules для отдельных подсистем | Rules loaded, firing/recovery и delivery |
| Logs/SIEM | Structured bounded events | Off-host immutable storage и поиск |
| Host monitor | systemd unit/timer и Telegram transition alert | Units enabled и конечный канал работает |

## Расхождения и открытые решения

- Production Compose не содержит Prometheus/Alertmanager services; внешний monitoring boundary не
  описан как развёрнутый факт.
- Нет общего stale-backup alert и репозиторного evidence доставки каждого critical alert.
- Не определены SLO/error budgets и единая severity/ownership matrix.

## Проверка

Unit tests проверяют token authorization, schema readiness, bounded SIEM events и provider health.
Runtime acceptance должна отдельно подтвердить scrape, загрузку rules, fire/recovery и конечную
доставку без утечки секретов.

## Триггеры пересмотра

Новые management endpoints, metrics, labels, alert rules, log sinks, health semantics или monitoring
topology требуют обновления документа и соответствующего runbook.
