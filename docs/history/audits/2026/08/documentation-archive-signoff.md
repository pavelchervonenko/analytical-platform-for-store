---
doc_schema: 1
doc_type: evidence
status: historical
owner: project
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS
verdict_scope: Independent verification of the 105-document history/archive migration.
source_of_truth:
  - docs/maintenance/documentation-migration-map.md
  - git:fd5e1383f72c
required_reviewers:
  - information-architecture
---

# Sign-off переноса документации

Независимый reviewer Huygens подтвердил физическую миграцию без P1/P2.

Проверено:

- 105 source, 105 уникальных destination и 105 staged rename;
- 105/105 исходных SHA-256 совпадают с blob до переноса;
- 82 тела идентичны после CRLF→LF, ещё 23 отличаются только relocation Markdown-link targets;
- локальные Markdown-ссылки: 0 ошибок и 0 предупреждений;
- metadata исчезнувших путей в 84 current/runbook/security документах: 0;
- docs/prompts, docs/schemas, AGENTS.md, .github и .codex-prod-recovery не вошли в перенос.

## Подтверждённые пути

| Исходный путь | Сохранённый путь |
|---|---|
| `docs/AI_INTERPRETATION_FULL_AUDIT_2026-08-14.md` | `docs/history/audits/2026/08/AI_INTERPRETATION_FULL_AUDIT_2026-08-14.md` |
| `docs/AI_INTERPRETATION_V15_RELEASE_HANDOFF_2026-08-17.md` | `docs/history/handoffs/2026/08/AI_INTERPRETATION_V15_RELEASE_HANDOFF_2026-08-17.md` |
| `docs/AI_INTERPRETATION_V19_RELEASE_HANDOFF_2026-08-17.md` | `docs/history/handoffs/2026/08/AI_INTERPRETATION_V19_RELEASE_HANDOFF_2026-08-17.md` |
| `docs/AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md` | `docs/history/canaries/2026/08/AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md` |
| `docs/AI_WEEKLY_REDESIGN_STAGE1_AUDIT.md` | `docs/history/audits/2026/08/AI_WEEKLY_REDESIGN_STAGE1_AUDIT.md` |
| `docs/AI_WEEKLY_REDESIGN_STAGE2_API_CONTRACT.md` | `docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_API_CONTRACT.md` |
| `docs/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md` | `docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md` |
| `docs/AI_WEEKLY_REDESIGN_STAGE5_UI_CONTRACT.md` | `docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE5_UI_CONTRACT.md` |
| `docs/AI_WEEKLY_REDESIGN_WORKLOG.md` | `docs/history/handoffs/2026/08/AI_WEEKLY_REDESIGN_WORKLOG.md` |
| `docs/CHECKPOINT_2026-08-20_CALENDAR_CLASSIFICATION.md` | `docs/history/handoffs/2026/08/CHECKPOINT_2026-08-20_CALENDAR_CLASSIFICATION.md` |
| `docs/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md` | `docs/history/audits/2026/08/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md` |
| `docs/DESIGN-apple.md` | `docs/archive/superseded-designs/DESIGN-apple.md` |
| `docs/FRONTEND_ACCEPTANCE.md` | `docs/history/audits/2026/08/FRONTEND_ACCEPTANCE.md` |
| `docs/FRONTEND_HANDOFF.md` | `docs/history/handoffs/2026/08/FRONTEND_HANDOFF.md` |
| `docs/METRICS_AUDIT_2026-08-20.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20.md` |
| `docs/METRICS_AUDIT_2026-08-20_CATEGORIES_AND_CARDS.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_CATEGORIES_AND_CARDS.md` |
| `docs/METRICS_AUDIT_2026-08-20_CLASSIFICATION_SCOPE.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_CLASSIFICATION_SCOPE.md` |
| `docs/METRICS_AUDIT_2026-08-20_EMPLOYEES.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_EMPLOYEES.md` |
| `docs/METRICS_AUDIT_2026-08-20_PERIOD_MODE.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_PERIOD_MODE.md` |
| `docs/METRICS_AUDIT_2026-08-20_SUMMARY.md` | `docs/history/audits/2026/08/METRICS_AUDIT_2026-08-20_SUMMARY.md` |
| `docs/PRODUCTION_PILOT_CONTINUATION_HANDOFF_2026-08-11.md` | `docs/history/handoffs/2026/08/PRODUCTION_PILOT_CONTINUATION_HANDOFF_2026-08-11.md` |
| `docs/PRODUCTION_PILOT_WORKING_PRACTICES.md` | `docs/archive/legacy-contracts/PRODUCTION_PILOT_WORKING_PRACTICES.md` |
| `docs/PRODUCTION_RELEASE_V0.1.0_PILOT_14_2026-08-17.md` | `docs/history/releases/2026/08/PRODUCTION_RELEASE_V0.1.0_PILOT_14_2026-08-17.md` |
| `docs/PRODUCTION_RELEASE_V0.1.0_PILOT_15_2026-08-17.md` | `docs/history/releases/2026/08/PRODUCTION_RELEASE_V0.1.0_PILOT_15_2026-08-17.md` |
| `docs/PROJECT_HANDOFF.md` | `docs/history/handoffs/2026/08/PROJECT_HANDOFF.md` |
| `docs/RELEASE_CANDIDATE_2026-08-24.md` | `docs/history/releases/2026/08/RELEASE_CANDIDATE_2026-08-24.md` |
| `docs/REVENUE_RECONCILIATION_AUDIT_2026-08-18.md` | `docs/history/audits/2026/08/REVENUE_RECONCILIATION_AUDIT_2026-08-18.md` |
| `docs/REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md` | `docs/history/audits/2026/08/REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md` |
| `docs/analytics-business-rules-draft.md` | `docs/archive/discoveries/analytics-business-rules-draft.md` |
| `docs/architecture.md` | `docs/archive/legacy-contracts/architecture.md` |
| `docs/attach-rate-api.md` | `docs/archive/legacy-contracts/attach-rate-api.md` |
| `docs/audit-log.md` | `docs/archive/legacy-contracts/audit-log.md` |
| `docs/authentication-api.md` | `docs/archive/legacy-contracts/authentication-api.md` |
| `docs/average-kpi-api.md` | `docs/archive/legacy-contracts/average-kpi-api.md` |
| `docs/bootstrap-and-break-glass.md` | `docs/archive/legacy-contracts/bootstrap-and-break-glass.md` |
| `docs/category-kpi-api.md` | `docs/archive/legacy-contracts/category-kpi-api.md` |
| `docs/daily-store-pulse.md` | `docs/archive/legacy-contracts/daily-store-pulse.md` |
| `docs/data-quality-api.md` | `docs/archive/legacy-contracts/data-quality-api.md` |
| `docs/data-retention.md` | `docs/archive/legacy-contracts/data-retention.md` |
| `docs/database-design.md` | `docs/archive/legacy-contracts/database-design.md` |
| `docs/deployment-and-operations.md` | `docs/archive/legacy-contracts/deployment-and-operations.md` |
| `docs/employee-category-kpi-api.md` | `docs/archive/legacy-contracts/employee-category-kpi-api.md` |
| `docs/employee-kpi-api.md` | `docs/archive/legacy-contracts/employee-kpi-api.md` |
| `docs/employee-rating-api.md` | `docs/archive/legacy-contracts/employee-rating-api.md` |
| `docs/employee-rating-salary-discovery.md` | `docs/archive/discoveries/employee-rating-salary-discovery.md` |
| `docs/error-handling.md` | `docs/archive/legacy-contracts/error-handling.md` |
| `docs/frontend-actions.md` | `docs/archive/legacy-contracts/frontend-actions.md` |
| `docs/frontend-contract.md` | `docs/archive/legacy-contracts/frontend-contract.md` |
| `docs/livesklad-api-docs.md` | `docs/archive/legacy-contracts/livesklad-api-docs.md` |
| `docs/livesklad-webhook-receiver.md` | `docs/archive/legacy-contracts/livesklad-webhook-receiver.md` |
| `docs/llm-analysis-lifecycle.md` | `docs/archive/legacy-contracts/llm-analysis-lifecycle.md` |
| `docs/llm-analysis-planner.md` | `docs/archive/legacy-contracts/llm-analysis-planner.md` |
| `docs/llm-dashboard-read-projection.md` | `docs/archive/legacy-contracts/llm-dashboard-read-projection.md` |
| `docs/llm-fact-catalog-v1.md` | `docs/archive/legacy-contracts/llm-fact-catalog-v1.md` |
| `docs/llm-interpretation-publication.md` | `docs/archive/legacy-contracts/llm-interpretation-publication.md` |
| `docs/llm-notifications-design.md` | `docs/archive/legacy-contracts/llm-notifications-design.md` |
| `docs/llm-output-contract-v2.md` | `docs/archive/legacy-contracts/llm-output-contract-v2.md` |
| `docs/llm-production-operations.md` | `docs/archive/legacy-contracts/llm-production-operations.md` |
| `docs/llm-provider-worker.md` | `docs/archive/legacy-contracts/llm-provider-worker.md` |
| `docs/llm-response-validation.md` | `docs/archive/legacy-contracts/llm-response-validation.md` |
| `docs/observability.md` | `docs/archive/legacy-contracts/observability.md` |
| `docs/payroll-api.md` | `docs/archive/legacy-contracts/payroll-api.md` |
| `docs/payroll-classification-review.md` | `docs/history/audits/2026/08/payroll-classification-review.md` |
| `docs/period-quality-api.md` | `docs/archive/legacy-contracts/period-quality-api.md` |
| `docs/pilot-production-deployment.md` | `docs/history/releases/2026/08/pilot-production-deployment.md` |
| `docs/pilot-rollout-status.md` | `docs/history/releases/2026/08/pilot-rollout-status.md` |
| `docs/product-category-import-api.md` | `docs/archive/legacy-contracts/product-category-import-api.md` |
| `docs/production-deployment-runbook.md` | `docs/archive/legacy-contracts/production-deployment-runbook.md` |
| `docs/reports.md` | `docs/archive/legacy-contracts/reports.md` |
| `docs/resource-limits.md` | `docs/archive/legacy-contracts/resource-limits.md` |
| `docs/security-hardening.md` | `docs/archive/legacy-contracts/security-hardening.md` |
| `docs/store-data-status-api.md` | `docs/archive/legacy-contracts/store-data-status-api.md` |
| `docs/store-directory-api.md` | `docs/archive/legacy-contracts/store-directory-api.md` |
| `docs/store-kpi-api.md` | `docs/archive/legacy-contracts/store-kpi-api.md` |
| `docs/store-plan-progress-api.md` | `docs/archive/legacy-contracts/store-plan-progress-api.md` |
| `docs/supply-chain-security.md` | `docs/archive/legacy-contracts/supply-chain-security.md` |
| `docs/synchronization-api.md` | `docs/archive/legacy-contracts/synchronization-api.md` |
| `docs/telegram-delivery-alerting.md` | `docs/archive/legacy-contracts/telegram-delivery-alerting.md` |
| `docs/telegram-delivery-worker.md` | `docs/archive/legacy-contracts/telegram-delivery-worker.md` |
| `docs/telegram-linking-and-webhook.md` | `docs/archive/legacy-contracts/telegram-linking-and-webhook.md` |
| `docs/telegram-notification-fanout.md` | `docs/archive/legacy-contracts/telegram-notification-fanout.md` |
| `docs/telegram-staging-acceptance.md` | `docs/history/canaries/2026/08/telegram-staging-acceptance.md` |
| `docs/test-contour-llm-telegram-rollout.md` | `docs/history/releases/2026/08/test-contour-llm-telegram-rollout.md` |
| `docs/validated-return-recovery-runbook.md` | `docs/archive/legacy-contracts/validated-return-recovery-runbook.md` |
| `docs/weekly-analytics-facts-source.md` | `docs/archive/legacy-contracts/weekly-analytics-facts-source.md` |
| `docs/weekly-review-ai-management-rubric.md` | `docs/archive/legacy-contracts/weekly-review-ai-management-rubric.md` |
| `docs/weekly-review-v2-backend.md` | `docs/archive/legacy-contracts/weekly-review-v2-backend.md` |
| `docs/weekly-review-v22-ai-contract.md` | `docs/archive/legacy-contracts/weekly-review-v22-ai-contract.md` |
| `docs/weekly-review-v22-rollout.md` | `docs/history/releases/2026/08/weekly-review-v22-rollout.md` |
| `docs/weekly-review-v23-management-calibration.md` | `docs/history/audits/2026/08/weekly-review-v23-management-calibration.md` |
| `docs/weekly-review-v23-rollout.md` | `docs/history/releases/2026/08/weekly-review-v23-rollout.md` |
| `docs/weekly-review-v24-management-calibration.md` | `docs/history/audits/2026/08/weekly-review-v24-management-calibration.md` |
| `docs/weekly-review-v24-rollout.md` | `docs/history/releases/2026/08/weekly-review-v24-rollout.md` |
| `docs/weekly-review-v25-management-calibration.md` | `docs/history/audits/2026/08/weekly-review-v25-management-calibration.md` |
| `docs/weekly-review-v25-rollout.md` | `docs/history/canaries/2026/08/weekly-review-v25-rollout.md` |
| `docs/weekly-snapshot-alerting.md` | `docs/archive/legacy-contracts/weekly-snapshot-alerting.md` |
| `docs/weekly-snapshot-builder.md` | `docs/archive/legacy-contracts/weekly-snapshot-builder.md` |
| `docs/weekly-snapshot-execution.md` | `docs/archive/legacy-contracts/weekly-snapshot-execution.md` |
| `docs/weekly-snapshot-jobs.md` | `docs/archive/legacy-contracts/weekly-snapshot-jobs.md` |
| `docs/weekly-snapshot-operations.md` | `docs/archive/legacy-contracts/weekly-snapshot-operations.md` |
| `docs/weekly-snapshot-persistence.md` | `docs/archive/legacy-contracts/weekly-snapshot-persistence.md` |
| `docs/weekly-snapshot-planner.md` | `docs/archive/legacy-contracts/weekly-snapshot-planner.md` |
| `docs/weekly-snapshot-worker.md` | `docs/archive/legacy-contracts/weekly-snapshot-worker.md` |
| `docs/yandexgpt-adapter.md` | `docs/archive/legacy-contracts/yandexgpt-adapter.md` |
| `docs/yandexgpt-staging-acceptance.md` | `docs/history/canaries/2026/08/yandexgpt-staging-acceptance.md` |

Исходные пути можно сохранять как tombstone в inventory; повторное физическое удаление не требуется.
