# Документация Store Analytics

Этот индекс ведёт к действующим контрактам, runbook и immutable evidence. Текущее проверенное
production-состояние хранится только в [current/project-state.md](current/project-state.md).
Датированные release, audit, canary и handoff-файлы не являются текущими инструкциями.

## С чего начать

1. [current/project-state.md](current/project-state.md) — последнее проверенное runtime-состояние и
   честные ограничения evidence.
2. [maintenance/documentation-policy.md](maintenance/documentation-policy.md) — классы документов,
   источники истины и правила обновления.
3. [maintenance/documentation-reform-plan.md](maintenance/documentation-reform-plan.md) — этапы и
   текущая контрольная точка.
4. [maintenance/documentation-inventory.md](maintenance/documentation-inventory.md) — полный реестр
   действующих, исторических и устаревших материалов.

## Переходный индекс предметных документов

Раздел ниже временный: перечисленные файлы проходят сверку с кодом и переносятся в `current/`,
`runbooks/`, `security/`, `decisions/`, `history/` или `archive/`. Пока у файла нет статуса
`current` по новой политике, его содержание нужно подтверждать реализацией и тестами.

### Архитектура и эксплуатация

- [architecture.md](architecture.md)
- [database-design.md](database-design.md)
- [deployment-and-operations.md](deployment-and-operations.md)
- [production-deployment-runbook.md](production-deployment-runbook.md)
- [observability.md](observability.md)
- [security-hardening.md](security-hardening.md)
- [resource-limits.md](resource-limits.md)
- [data-retention.md](data-retention.md)
- [supply-chain-security.md](supply-chain-security.md)

### LiveSklad, синхронизация и данные

- [livesklad-api-docs.md](livesklad-api-docs.md)
- [synchronization-api.md](synchronization-api.md)
- [livesklad-webhook-receiver.md](livesklad-webhook-receiver.md)
- [validated-return-recovery-runbook.md](validated-return-recovery-runbook.md)
- [data-quality-api.md](data-quality-api.md)
- [period-quality-api.md](period-quality-api.md)
- [store-data-status-api.md](store-data-status-api.md)

### Метрики и интерфейс

- [analytics-business-rules-draft.md](analytics-business-rules-draft.md)
- [frontend-actions.md](frontend-actions.md)
- [frontend-contract.md](frontend-contract.md)
- [store-kpi-api.md](store-kpi-api.md)
- [category-kpi-api.md](category-kpi-api.md)
- [attach-rate-api.md](attach-rate-api.md)
- [store-plan-progress-api.md](store-plan-progress-api.md)
- [employee-kpi-api.md](employee-kpi-api.md)
- [employee-category-kpi-api.md](employee-category-kpi-api.md)
- [employee-rating-api.md](employee-rating-api.md)
- [payroll-api.md](payroll-api.md)
- [reports.md](reports.md)

### ИИ и уведомления

- [AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md](AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md)
- [llm-analysis-lifecycle.md](llm-analysis-lifecycle.md)
- [llm-output-contract-v2.md](llm-output-contract-v2.md)
- [llm-production-operations.md](llm-production-operations.md)
- [weekly-snapshot-operations.md](weekly-snapshot-operations.md)
- [telegram-linking-and-webhook.md](telegram-linking-and-webhook.md)
- [telegram-delivery-worker.md](telegram-delivery-worker.md)

## Аудиты и исторические снимки

Файлы с датой или версией в имени фиксируют состояние на момент проверки. Они не являются
инструкцией к текущему релизу и не переписываются при каждом изменении. К ним относятся
`*_AUDIT_*.md`, `CHECKPOINT_*.md`, `PRODUCTION_RELEASE_*.md`,
`AI_INTERPRETATION_*_HANDOFF_*.md` и `PRODUCTION_PILOT_*.md`.

Июльский [предварительный аудит до восстановления](REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md)
содержит устаревший `ACTION_REQUIRED` и не является инструкцией. Восстановление восьми возвратов
завершено; итог и запрет повторного запуска зафиксированы в
[current/project-state.md](current/project-state.md#data-and-return-recovery).

## Правила обновления

Нормативные правила, ownership, review gates и шаблоны находятся в
[maintenance/documentation-policy.md](maintenance/documentation-policy.md) и
[maintenance/documentation-ownership.md](maintenance/documentation-ownership.md). Production-факт
публикуется только из sanitized runtime evidence; секреты, полные environment-файлы и персональные
данные в документацию не включаются.
