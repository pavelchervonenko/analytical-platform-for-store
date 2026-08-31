---
doc_schema: 1
doc_type: working
status: draft
owner: project
audience:
  - developer
  - operator
created_at: 2026-08-31
review_by: 2026-09-07
source_material:
  - docs/maintenance/documentation-inventory.tsv
  - docs/current/README.md
required_reviewers:
  - information-architecture
exit_target: archive
---

# Карта миграции исторических и superseded документов

Карта фиксирует точный путь каждого корневого документа до физического перемещения. Перенос не
делает исходный текст текущим: третий столбец является единственной актуальной заменой.

## Gates

- Все источники сначала получают `delete-candidate` отдельным коммитом.
- Исторический текст переносится без смыслового переписывания и получает evidence metadata/banner.
- Superseded contract переносится в archive и получает ссылку на current replacement.
- Versioned prompts/schemas, maintenance и `docs/README.md` не входят в перенос.
- Удаление исходного пути допускается только после независимого sign-off этой карты.
- После переноса обновляются все repository references и выполняется strict docs-check.

## Полная карта

| Исходный путь | Новый путь | Актуальная замена |
|---|---|---|
| `docs/AI_INTERPRETATION_FULL_AUDIT_2026-08-14.md` | `docs/history/audits/2026/08/AI_INTERPRETATION_FULL_AUDIT_2026-08-14.md` | `docs/current/ai/README.md` |
| `docs/AI_INTERPRETATION_V15_RELEASE_HANDOFF_2026-08-17.md` | `docs/history/handoffs/2026/08/AI_INTERPRETATION_V15_RELEASE_HANDOFF_2026-08-17.md` | `docs/current/ai/README.md` |
| `docs/AI_INTERPRETATION_V19_RELEASE_HANDOFF_2026-08-17.md` | `docs/history/handoffs/2026/08/AI_INTERPRETATION_V19_RELEASE_HANDOFF_2026-08-17.md` | `docs/current/ai/README.md` |
| `docs/AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md` | `docs/history/canaries/2026/08/AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md` | `docs/current/ai/README.md` |
| `docs/AI_WEEKLY_REDESIGN_STAGE1_AUDIT.md` | `docs/history/audits/2026/08/AI_WEEKLY_REDESIGN_STAGE1_AUDIT.md` | `docs/current/ai/README.md` |
| `docs/AI_WEEKLY_REDESIGN_STAGE2_API_CONTRACT.md` | `docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_API_CONTRACT.md` | `docs/current/ai/README.md` |
| `docs/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md` | `docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md` | `docs/current/ai/README.md` |
| `docs/AI_WEEKLY_REDESIGN_STAGE5_UI_CONTRACT.md` | `docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE5_UI_CONTRACT.md` | `docs/current/ai/README.md` |
| `docs/AI_WEEKLY_REDESIGN_WORKLOG.md` | `docs/history/handoffs/2026/08/AI_WEEKLY_REDESIGN_WORKLOG.md` | `docs/current/ai/README.md` |
| `docs/CHECKPOINT_2026-08-20_CALENDAR_CLASSIFICATION.md` | `docs/history/handoffs/2026/08/CHECKPOINT_2026-08-20_CALENDAR_CLASSIFICATION.md` | `docs/current/product/README.md` |
| `docs/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md` | `docs/history/audits/2026/08/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md` | `docs/current/product/README.md` |
| `docs/DESIGN-apple.md` | `docs/archive/superseded-designs/DESIGN-apple.md` | `docs/current/frontend/README.md` |
| `docs/FRONTEND_ACCEPTANCE.md` | `docs/history/audits/2026/08/FRONTEND_ACCEPTANCE.md` | `docs/runbooks/frontend-acceptance.md` (draft; только local/test) |
| `docs/FRONTEND_HANDOFF.md` | `docs/history/handoffs/2026/08/FRONTEND_HANDOFF.md` | `docs/current/frontend/README.md` |
| `docs/METRICS_AUDIT_2026-08-20.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20.md` | `docs/current/product/README.md` |
| `docs/METRICS_AUDIT_2026-08-20_CATEGORIES_AND_CARDS.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_CATEGORIES_AND_CARDS.md` | `docs/current/product/README.md` |
| `docs/METRICS_AUDIT_2026-08-20_CLASSIFICATION_SCOPE.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_CLASSIFICATION_SCOPE.md` | `docs/current/product/README.md` |
| `docs/METRICS_AUDIT_2026-08-20_EMPLOYEES.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_EMPLOYEES.md` | `docs/current/product/README.md` |
| `docs/METRICS_AUDIT_2026-08-20_PERIOD_MODE.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_PERIOD_MODE.md` | `docs/current/product/README.md` |
| `docs/METRICS_AUDIT_2026-08-20_SUMMARY.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_SUMMARY.md` | `docs/current/product/README.md` |
| `docs/PRODUCTION_PILOT_CONTINUATION_HANDOFF_2026-08-11.md` | `docs/history/handoffs/2026/08/PRODUCTION_PILOT_CONTINUATION_HANDOFF_2026-08-11.md` | `docs/current/project-state.md` |
| `docs/PRODUCTION_PILOT_WORKING_PRACTICES.md` | `docs/archive/legacy-contracts/PRODUCTION_PILOT_WORKING_PRACTICES.md` | `AGENTS.md` |
| `docs/PRODUCTION_RELEASE_V0.1.0_PILOT_14_2026-08-17.md` | `docs/history/releases/2026/08/PRODUCTION_RELEASE_V0.1.0_PILOT_14_2026-08-17.md` | `docs/current/project-state.md` |
| `docs/PRODUCTION_RELEASE_V0.1.0_PILOT_15_2026-08-17.md` | `docs/history/releases/2026/08/PRODUCTION_RELEASE_V0.1.0_PILOT_15_2026-08-17.md` | `docs/current/project-state.md` |
| `docs/PROJECT_HANDOFF.md` | `docs/history/handoffs/2026/08/PROJECT_HANDOFF.md` | `docs/current/project-state.md` |
| `docs/RELEASE_CANDIDATE_2026-08-24.md` | `docs/history/releases/2026/08/RELEASE_CANDIDATE_2026-08-24.md` | `docs/current/project-state.md` |
| `docs/REVENUE_RECONCILIATION_AUDIT_2026-08-18.md` | `docs/history/audits/2026/08/REVENUE_RECONCILIATION_AUDIT_2026-08-18.md` | `docs/current/product/README.md` |
| `docs/REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md` | `docs/history/audits/2026/08/REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md` | `docs/current/product/README.md` |
| `docs/analytics-business-rules-draft.md` | `docs/archive/discoveries/analytics-business-rules-draft.md` | `docs/current/product/README.md` |
| `docs/architecture.md` | `docs/archive/legacy-contracts/architecture.md` | `docs/current/architecture/application.md` |
| `docs/attach-rate-api.md` | `docs/archive/legacy-contracts/attach-rate-api.md` | `docs/current/api/attach-rate.md` |
| `docs/audit-log.md` | `docs/archive/legacy-contracts/audit-log.md` | `docs/current/architecture/audit-and-telemetry.md` |
| `docs/authentication-api.md` | `docs/archive/legacy-contracts/authentication-api.md` | `docs/current/api/authentication.md` |
| `docs/average-kpi-api.md` | `docs/archive/legacy-contracts/average-kpi-api.md` | `docs/current/api/average-kpi.md` |
| `docs/bootstrap-and-break-glass.md` | `docs/archive/legacy-contracts/bootstrap-and-break-glass.md` | `docs/runbooks/access-and-break-glass.md` (draft; production NO-GO) |
| `docs/category-kpi-api.md` | `docs/archive/legacy-contracts/category-kpi-api.md` | `docs/current/api/category-kpi.md` |
| `docs/daily-store-pulse.md` | `docs/archive/legacy-contracts/daily-store-pulse.md` | `docs/current/product/daily-store-pulse.md`; `docs/runbooks/daily-store-pulse.md` (draft) |
| `docs/data-quality-api.md` | `docs/archive/legacy-contracts/data-quality-api.md` | `docs/current/api/data-quality.md` |
| `docs/data-retention.md` | `docs/archive/legacy-contracts/data-retention.md` | `docs/security/data-retention.md` |
| `docs/database-design.md` | `docs/archive/legacy-contracts/database-design.md` | `docs/current/architecture/database.md` |
| `docs/deployment-and-operations.md` | `docs/archive/legacy-contracts/deployment-and-operations.md` | `docs/runbooks/README.md` |
| `docs/employee-category-kpi-api.md` | `docs/archive/legacy-contracts/employee-category-kpi-api.md` | `docs/current/api/employee-category-kpi.md` |
| `docs/employee-kpi-api.md` | `docs/archive/legacy-contracts/employee-kpi-api.md` | `docs/current/api/employee-kpi.md` |
| `docs/employee-rating-api.md` | `docs/archive/legacy-contracts/employee-rating-api.md` | `docs/current/api/employee-rating.md` |
| `docs/employee-rating-salary-discovery.md` | `docs/archive/discoveries/employee-rating-salary-discovery.md` | `docs/current/product/employees-and-rating.md` |
| `docs/error-handling.md` | `docs/archive/legacy-contracts/error-handling.md` | `docs/current/architecture/error-handling.md` |
| `docs/frontend-actions.md` | `docs/archive/legacy-contracts/frontend-actions.md` | `docs/current/frontend/data-quality-actions.md` |
| `docs/frontend-contract.md` | `docs/archive/legacy-contracts/frontend-contract.md` | `docs/current/frontend/README.md` |
| `docs/livesklad-api-docs.md` | `docs/archive/legacy-contracts/livesklad-api-docs.md` | `docs/current/integrations/livesklad/observed-api.md` |
| `docs/livesklad-webhook-receiver.md` | `docs/archive/legacy-contracts/livesklad-webhook-receiver.md` | `docs/current/integrations/livesklad/webhooks.md` |
| `docs/llm-analysis-lifecycle.md` | `docs/archive/legacy-contracts/llm-analysis-lifecycle.md` | `docs/current/ai/README.md` |
| `docs/llm-analysis-planner.md` | `docs/archive/legacy-contracts/llm-analysis-planner.md` | `docs/current/ai/README.md` |
| `docs/llm-dashboard-read-projection.md` | `docs/archive/legacy-contracts/llm-dashboard-read-projection.md` | `docs/current/ai/README.md` |
| `docs/llm-fact-catalog-v1.md` | `docs/archive/legacy-contracts/llm-fact-catalog-v1.md` | `docs/current/ai/README.md` |
| `docs/llm-interpretation-publication.md` | `docs/archive/legacy-contracts/llm-interpretation-publication.md` | `docs/current/ai/README.md` |
| `docs/llm-notifications-design.md` | `docs/archive/legacy-contracts/llm-notifications-design.md` | `docs/current/ai/README.md` |
| `docs/llm-output-contract-v2.md` | `docs/archive/legacy-contracts/llm-output-contract-v2.md` | `docs/current/ai/README.md` |
| `docs/llm-production-operations.md` | `docs/archive/legacy-contracts/llm-production-operations.md` | `docs/current/ai/README.md` |
| `docs/llm-provider-worker.md` | `docs/archive/legacy-contracts/llm-provider-worker.md` | `docs/current/ai/README.md` |
| `docs/llm-response-validation.md` | `docs/archive/legacy-contracts/llm-response-validation.md` | `docs/current/ai/README.md` |
| `docs/observability.md` | `docs/archive/legacy-contracts/observability.md` | `docs/current/architecture/observability.md` |
| `docs/payroll-api.md` | `docs/archive/legacy-contracts/payroll-api.md` | `docs/current/api/payroll.md` |
| `docs/payroll-classification-review.md` | `docs/history/audits/2026/08/payroll-classification-review.md` | `docs/current/product/payroll.md` |
| `docs/period-quality-api.md` | `docs/archive/legacy-contracts/period-quality-api.md` | `docs/current/api/period-quality.md` |
| `docs/pilot-production-deployment.md` | `docs/history/releases/2026/08/pilot-production-deployment.md` | `docs/runbooks/production-deployment.md` (draft; production NO-GO) |
| `docs/pilot-rollout-status.md` | `docs/history/releases/2026/08/pilot-rollout-status.md` | `docs/current/project-state.md` |
| `docs/product-category-import-api.md` | `docs/archive/legacy-contracts/product-category-import-api.md` | `docs/current/api/product-category-import.md` |
| `docs/production-deployment-runbook.md` | `docs/archive/legacy-contracts/production-deployment-runbook.md` | `docs/runbooks/production-deployment.md` (draft; production NO-GO) |
| `docs/reports.md` | `docs/archive/legacy-contracts/reports.md` | `docs/current/product/reports.md` |
| `docs/resource-limits.md` | `docs/archive/legacy-contracts/resource-limits.md` | `docs/current/architecture/resource-limits.md` |
| `docs/security-hardening.md` | `docs/archive/legacy-contracts/security-hardening.md` | `docs/security/baseline.md` |
| `docs/store-data-status-api.md` | `docs/archive/legacy-contracts/store-data-status-api.md` | `docs/current/api/store-data-status.md` |
| `docs/store-directory-api.md` | `docs/archive/legacy-contracts/store-directory-api.md` | `docs/current/api/store-directory.md` |
| `docs/store-kpi-api.md` | `docs/archive/legacy-contracts/store-kpi-api.md` | `docs/current/api/store-kpi.md` |
| `docs/store-plan-progress-api.md` | `docs/archive/legacy-contracts/store-plan-progress-api.md` | `docs/current/api/store-plan-progress.md` |
| `docs/supply-chain-security.md` | `docs/archive/legacy-contracts/supply-chain-security.md` | `docs/security/supply-chain.md` |
| `docs/synchronization-api.md` | `docs/archive/legacy-contracts/synchronization-api.md` | `docs/current/integrations/livesklad/synchronization.md` |
| `docs/telegram-delivery-alerting.md` | `docs/archive/legacy-contracts/telegram-delivery-alerting.md` | `docs/current/ai/telegram.md` |
| `docs/telegram-delivery-worker.md` | `docs/archive/legacy-contracts/telegram-delivery-worker.md` | `docs/current/ai/telegram.md` |
| `docs/telegram-linking-and-webhook.md` | `docs/archive/legacy-contracts/telegram-linking-and-webhook.md` | `docs/current/ai/telegram.md` |
| `docs/telegram-notification-fanout.md` | `docs/archive/legacy-contracts/telegram-notification-fanout.md` | `docs/current/ai/telegram.md` |
| `docs/telegram-staging-acceptance.md` | `docs/history/canaries/2026/08/telegram-staging-acceptance.md` | `docs/current/ai/telegram.md` |
| `docs/test-contour-llm-telegram-rollout.md` | `docs/history/releases/2026/08/test-contour-llm-telegram-rollout.md` | `docs/current/ai/telegram.md` |
| `docs/validated-return-recovery-runbook.md` | `docs/archive/legacy-contracts/validated-return-recovery-runbook.md` | `docs/runbooks/livesklad-return-recovery.md` (draft; production NO-GO) |
| `docs/weekly-analytics-facts-source.md` | `docs/archive/legacy-contracts/weekly-analytics-facts-source.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-ai-management-rubric.md` | `docs/archive/legacy-contracts/weekly-review-ai-management-rubric.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v2-backend.md` | `docs/archive/legacy-contracts/weekly-review-v2-backend.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v22-ai-contract.md` | `docs/archive/legacy-contracts/weekly-review-v22-ai-contract.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v22-rollout.md` | `docs/history/releases/2026/08/weekly-review-v22-rollout.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v23-management-calibration.md` | `docs/history/audits/2026/08/weekly-review-v23-management-calibration.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v23-rollout.md` | `docs/history/releases/2026/08/weekly-review-v23-rollout.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v24-management-calibration.md` | `docs/history/audits/2026/08/weekly-review-v24-management-calibration.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v24-rollout.md` | `docs/history/releases/2026/08/weekly-review-v24-rollout.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v25-management-calibration.md` | `docs/history/audits/2026/08/weekly-review-v25-management-calibration.md` | `docs/current/ai/README.md` |
| `docs/weekly-review-v25-rollout.md` | `docs/history/canaries/2026/08/weekly-review-v25-rollout.md` | `docs/current/ai/weekly-review.md`; `docs/runbooks/weekly-review-ai.md` (draft; paid canary NO-GO) |
| `docs/weekly-snapshot-alerting.md` | `docs/archive/legacy-contracts/weekly-snapshot-alerting.md` | `docs/current/ai/README.md` |
| `docs/weekly-snapshot-builder.md` | `docs/archive/legacy-contracts/weekly-snapshot-builder.md` | `docs/current/ai/README.md` |
| `docs/weekly-snapshot-execution.md` | `docs/archive/legacy-contracts/weekly-snapshot-execution.md` | `docs/current/ai/README.md` |
| `docs/weekly-snapshot-jobs.md` | `docs/archive/legacy-contracts/weekly-snapshot-jobs.md` | `docs/current/ai/README.md` |
| `docs/weekly-snapshot-operations.md` | `docs/archive/legacy-contracts/weekly-snapshot-operations.md` | `docs/current/ai/README.md` |
| `docs/weekly-snapshot-persistence.md` | `docs/archive/legacy-contracts/weekly-snapshot-persistence.md` | `docs/current/ai/README.md` |
| `docs/weekly-snapshot-planner.md` | `docs/archive/legacy-contracts/weekly-snapshot-planner.md` | `docs/current/ai/README.md` |
| `docs/weekly-snapshot-worker.md` | `docs/archive/legacy-contracts/weekly-snapshot-worker.md` | `docs/current/ai/README.md` |
| `docs/yandexgpt-adapter.md` | `docs/archive/legacy-contracts/yandexgpt-adapter.md` | `docs/current/ai/providers/yandexgpt.md` |
| `docs/yandexgpt-staging-acceptance.md` | `docs/history/canaries/2026/08/yandexgpt-staging-acceptance.md` | `docs/current/ai/README.md` |

## Обновление ссылок после переноса

После `git mv` необходимо заменить каждую ссылку на исходный путь во всех tracked Markdown,
source/verification metadata и repository scripts. Точный список формируется поиском по 105 исходным
путям из этой таблицы; перенос не принимается, пока строгая проверка ссылок и повторный поиск не
возвращают ноль старых ссылок вне tombstone-строк inventory, этой карты и reviewer sign-off.
