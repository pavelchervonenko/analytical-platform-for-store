---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
  - operator
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - docs/current
verification_sources:
  - scripts/check-documentation.py
runtime_evidence: []
required_reviewers:
  - information-architecture
review_triggers:
  - documentation-structure-change
  - current-contract-change
supersedes: []
superseded_by: null
---

# Действующие контракты

Этот каталог описывает реализованное поведение. Он не хранит release history и не превращает
неподтверждённый operational шаг в инструкцию. Фактический production runtime находится только в
[project-state.md](project-state.md).

## Архитектура и эксплуатационные свойства

- [Application](architecture/application.md)
- [Database](architecture/database.md)
- [Migrations](architecture/migrations.md)
- [Error handling](architecture/error-handling.md)
- [Observability](architecture/observability.md)
- [Resilience и backup](architecture/resilience-and-backup.md)
- [Resource limits](architecture/resource-limits.md)
- [Audit и telemetry](architecture/audit-and-telemetry.md)

## HTTP API

- [API index](api/README.md)
- [Authentication](api/authentication.md)
- [Store directory](api/store-directory.md)
- [Store data status](api/store-data-status.md)
- [Data quality](api/data-quality.md)
- [Period quality](api/period-quality.md)
- [Reports](api/reports.md)
- [Product category import](api/product-category-import.md)
- [Store KPI](api/store-kpi.md)
- [Employee KPI](api/employee-kpi.md)
- [Category KPI](api/category-kpi.md)
- [Employee category KPI](api/employee-category-kpi.md)
- [Average KPI](api/average-kpi.md)
- [Attach-rate](api/attach-rate.md)
- [Employee rating](api/employee-rating.md)
- [Store plan progress](api/store-plan-progress.md)
- [Payroll](api/payroll.md)

## LiveSklad

- [Наблюдаемый API](integrations/livesklad/observed-api.md)
- [Синхронизация](integrations/livesklad/synchronization.md)
- [Webhook](integrations/livesklad/webhooks.md)
- [Recovery](integrations/livesklad/recovery.md)

## Продуктовые правила

- [Product index](product/README.md)
- [Бизнес-показатели](product/business-metrics.md)
- [Периоды](product/periods.md)
- [Классификация](product/classification.md)
- [Продажи и возвраты](product/sales-and-returns.md)
- [Attach-rate](product/attach-rate.md)
- [Планы и смены](product/plans-and-shifts.md)
- [Сотрудники и рейтинг](product/employees-and-rating.md)
- [Зарплата](product/payroll.md)
- [Отчёты](product/reports.md)
- [Качество данных](product/data-quality.md)

## Frontend

- [Frontend index](frontend/README.md)
- [Период и scope](frontend/period-and-scope-contract.md)
- [Главная](frontend/overview.md)
- [Структура продаж и карта допродаж](frontend/sales-structure-and-attach-map.md)
- [Сотрудники](frontend/employees.md)
- [План и смены](frontend/plan-and-shifts.md)
- [Зарплата и отчёты](frontend/payroll-and-reports.md)
- [Действия качества](frontend/data-quality-actions.md)
- [Transport-контракты](frontend/transport-contracts.md)

## AI и Telegram

- [AI index](ai/README.md)
- [Weekly Review v25/schema4](ai/weekly-review.md)
- [Legacy LLM](ai/legacy-llm.md)
- [Runtime-артефакты](ai/runtime-artifacts.md)
- [YandexGPT](ai/providers/yandexgpt.md)
- [Telegram](ai/telegram.md)
- [Privacy и retention](ai/privacy-and-retention.md)

## Связанные разделы

- [Runbooks](../runbooks/README.md) — процедуры с уровнем доказанности и stop-условиями.
- [Security](../security/README.md) — границы доверия, controls и риски.
- [Decisions](../decisions/README.md) — принятые и proposed решения.
