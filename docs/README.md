# Документация Store Analytics

Актуальность индекса: 2026-08-24.

Этот файл разделяет действующие документы и исторические снимки. Если сведения расходятся,
приоритет имеют код, миграции, автоматические проверки и документы из раздела «Актуальные».

## Текущее состояние

- production: `v0.1.0-pilot.22`, commit `2e8f9c2`, schema `V44`;
- текущий релиз-кандидат: ветка `codex/livesklad-daily-webhook-protection`, пять продуктовых commit и один документационный commit поверх
  production; он проверен локально, но еще не отправлен и не развернут;
- приемник возвратных webhook и worker возвратов продаж включены; worker возвратов заказов остается
  выключенным до проверки настоящего `ORDER_RETURN`;
- ИИ-схема `v21/schema3` прошла отдельный семантический прогон, но production default пока
  `v4/schema2`.

Сводка релиз-кандидата и точные ограничения находятся в
[RELEASE_CANDIDATE_2026-08-24.md](RELEASE_CANDIDATE_2026-08-24.md).

## С чего начать

1. [PROJECT_HANDOFF.md](PROJECT_HANDOFF.md) — архитектура, состояние production и открытые задачи.
2. [RELEASE_CANDIDATE_2026-08-24.md](RELEASE_CANDIDATE_2026-08-24.md) — что войдет в ближайший
   релиз и что намеренно исключено.
3. [FRONTEND_HANDOFF.md](FRONTEND_HANDOFF.md) и
   [FRONTEND_ACCEPTANCE.md](FRONTEND_ACCEPTANCE.md) — UI-контракт и фактическая проверка SPA.
4. [livesklad-webhook-receiver.md](livesklad-webhook-receiver.md) — прием и обработка возвратных
   webhook.
5. [validated-return-recovery-runbook.md](validated-return-recovery-runbook.md) — безопасное
   восстановление известных пропущенных возвратов.
6. [production-deployment-runbook.md](production-deployment-runbook.md) — обязательный порядок
   production-релиза и отката.

## Актуальные документы

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

Актуальный результат июльской сверки:
[REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md](REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md).

## Правила обновления

- изменение API сопровождается правкой тематического контракта;
- новая миграция отражается в `PROJECT_HANDOFF.md`, `database-design.md` и release note;
- изменение UI сопровождается обновлением `frontend-actions.md` и локальной визуальной проверкой;
- production-факт записывается только после фактического развертывания;
- секреты, реальные токены, пароли и персональные данные в документацию не попадают;
- временные production-скрипты не считаются релизным артефактом без dry-run, защит окружения,
  тестов и отдельного review.
