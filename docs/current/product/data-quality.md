---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/data-quality-api.md
  - docs/archive/legacy-contracts/period-quality-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/quality/service/StorePeriodQualityService.java
  - backend/src/main/java/com/storeanalytics/quality/repository/PeriodQualityIssueRepository.java
  - frontend/src/quality/presentation.ts
  - frontend/src/quality/actions.ts
verification_sources:
  - backend/src/test/java/com/storeanalytics/quality/service/StorePeriodQualityServiceTest.java
  - backend/src/test/java/com/storeanalytics/quality/service/StorePeriodQualityTransactionIntegrationTest.java
  - frontend/src/quality/issue-groups.test.ts
runtime_evidence: []
required_reviewers:
  - product
  - backend
  - frontend
review_triggers:
  - quality-rule-change
  - classification-change
  - metric-change
supersedes: []
superseded_by: null
---

# Качество и готовность данных

`ERROR` блокирует `readyForDecisions`; `WARNING` снижает уверенность, но сейчас не блокирует.
`null` означает недоступное/неполное, не ноль. Payroll отдельно имеет `canCalculate` и
`canApprove`; план читается вместе с `completeThroughAsOf` и `classificationComplete`.

| Случай | Поведение |
|---|---|
| `UNMAPPED` analytics | В store revenue, не в группах |
| `EXCLUDE` | Не участвует |
| Missing cost | Revenue/quantity есть; cost/GP/margin=`null` |
| `ZERO_SERVICE` | Допустимый ноль service/warranty/protection |
| `ZERO_UNEXPECTED` | Сейчас может остаться нулём и завысить GP |
| Attach ambiguity | Rate может быть числом с quality counters |
| Missing shifts | Store KPI есть; payroll readiness снижен |

## Подтверждённые gaps

1. `SOURCE_PRODUCTS_UNMAPPED` ведёт в payroll classification, не исправляющую analytics assignment.
2. `ZERO_UNEXPECTED` не всегда снижает readiness и не делает GP `null`.
3. Нет runtime gates `sum(full employees)=store` и `Допы=Аксессуары+Услуги`.
4. Attach-rate может быть рассчитан при ambiguity, поэтому quality context обязателен.

Правильные действия: sync gap — дождаться/запустить sync; analytics unmapped — назначить analytics
category отдельным инструментом; payroll unmapped — payroll form; cost — исправить источник и
пересинхронизировать; source mismatch — проверить документ и безопасно повторить загрузку.
