---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-09-10
requirement_sources:
  - docs/archive/legacy-contracts/data-quality-api.md
  - docs/archive/legacy-contracts/period-quality-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewQualityPolicyV1.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductCategoryImportService.java
  - backend/src/main/java/com/storeanalytics/quality/service/StorePeriodQualityService.java
  - backend/src/main/java/com/storeanalytics/quality/repository/PeriodQualityIssueRepository.java
  - frontend/src/admin/CategoryImportPanel.tsx
  - frontend/src/quality/presentation.ts
  - frontend/src/quality/actions.ts
verification_sources:
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewAssemblerTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductCategoryImportIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/quality/service/StorePeriodQualityServiceTest.java
  - backend/src/test/java/com/storeanalytics/quality/service/StorePeriodQualityTransactionIntegrationTest.java
  - frontend/src/quality/actions.test.ts
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

Полный реестр проверок, severity, технические причины и действия находится в разделе
«Качество данных» и доступен только `ADMIN`. Менеджер магазина не получает этот реестр ни через
маршрут, ни через quality API. В рабочих разделах менеджеру показывается только локальное
последствие для показателя и доступное ему действие; исправления источников передаются
администратору без раскрытия внутренних кодов и количества дефектных записей.

`ERROR` блокирует `readyForDecisions`; `WARNING` снижает уверенность, но сейчас не блокирует.
`null` означает недоступное/неполное, не ноль. Payroll отдельно имеет `canCalculate` и
`canApprove`; план читается вместе с `completeThroughAsOf` и `classificationComplete`.

В Weekly Review счётчики качества вычисляются только по текущей и предыдущей сравниваемым неделям.
Открытая проблема магазина за пределами этих периодов не ограничивает отчёт. Внутри периода каждая
проблема ограничивает только связанные блоки и метрики.

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

1. `ZERO_UNEXPECTED` не всегда снижает readiness и не делает GP `null`.
2. Нет runtime gates `sum(full employees)=store` и `Допы=Аксессуары+Услуги`.
3. Attach-rate может быть рассчитан при ambiguity, поэтому quality context обязателен.

Правильные действия: sync gap — дождаться/запустить sync; analytics unmapped — назначить analytics
category в разделе «Категории аналитики»; payroll unmapped — использовать отдельный раздел
«Категории зарплаты»; cost — исправить источник и пересинхронизировать; source mismatch — проверить
документ и безопасно повторить загрузку.
