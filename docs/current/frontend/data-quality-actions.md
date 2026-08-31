---
doc_schema: 1
doc_type: current
status: current
owner: frontend
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/current/product/data-quality.md
  - docs/current/product/classification.md
implementation_sources:
  - frontend/src/quality/actions.ts
  - frontend/src/quality/presentation.ts
  - frontend/src/admin/ClassificationPanel.tsx
verification_sources:
  - frontend/src/quality/issue-groups.test.ts
  - backend/src/test/java/com/storeanalytics/quality/service/StorePeriodQualityServiceTest.java
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - quality-action-change
  - classification-ui-change
  - permissions-change
supersedes: []
superseded_by: null
---

# Действия по качеству данных

Action должно менять модель, породившую issue, учитывать роль и честно объяснять отсутствие ручного
исправления.

| Issue/action | Правильная цель | Реализация |
|---|---|---|
| Sync gap | Refresh/admin sync | Есть с role guard |
| Missing plan/shifts | `/plan`, нужная view | Есть |
| Payroll unmapped | Payroll classification | Есть |
| `SOURCE_PRODUCTS_UNMAPPED` | Analytics assignment | **Неверно:** payroll form |
| Missing/unexpected cost | Source + resync | Manual editor отсутствует |
| Source mismatch | Source review/admin sync | Есть с role guard |

`ClassificationPanel` меняет `payroll_category_code`, не analytics effective-dated assignment.
Кнопка «Исправить категории» поэтому может не закрыть `SOURCE_PRODUCTS_UNMAPPED`; до analytics UI
она должна считаться incomplete remediation.

`ZERO_UNEXPECTED` не всегда блокирует readiness и GP может остаться числом. Текст обязан говорить
о возможной недостоверности прибыли; действие — исправить источник и пересинхронизировать.
