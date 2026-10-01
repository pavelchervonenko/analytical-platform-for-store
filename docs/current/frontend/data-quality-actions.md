---
doc_schema: 1
doc_type: current
status: current
owner: frontend
audience:
  - developer
  - operator
last_verified: 2026-09-20
requirement_sources:
  - docs/current/product/data-quality.md
  - docs/current/product/classification.md
implementation_sources:
  - frontend/src/quality/actions.ts
  - frontend/src/quality/presentation.ts
  - frontend/src/admin/CategoryImportPanel.tsx
  - frontend/src/admin/ClassificationPanel.tsx
verification_sources:
  - frontend/src/quality/actions.test.ts
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

Экран и его action routing доступны только администратору. Role guard в descriptor остаётся
fail-closed: попытка описать `REVIEW_DATA_ISSUES`, sync или classification для менеджера не создаёт
ссылку в закрытый раздел.

Action должно менять модель, породившую issue, учитывать роль и честно объяснять отсутствие ручного
исправления.

| Issue/action | Правильная цель | Реализация |
|---|---|---|
| Sync gap | Refresh/admin sync | Есть с role guard |
| Missing plan/shifts | `/plan` или `/shifts` | Есть |
| Payroll unmapped | Payroll classification | Есть |
| `SOURCE_PRODUCTS_UNMAPPED` | Analytics assignment | Есть с admin guard |
| Missing/unexpected cost | Source + resync | Manual editor отсутствует |
| Source mismatch | Source review/admin sync | Есть с role guard |

`ClassificationPanel` меняет `payroll_category_code`, не analytics effective-dated assignment.
В интерфейсе он явно называется «Категории зарплаты».

`CategoryImportPanel` меняет analytics assignment и называется «Категории аналитики». Admin action
`CLASSIFY_PRODUCTS` ведёт в этот раздел, а не в payroll form.

После успешного импорта backend повторно классифицирует только активные `UNMAPPED`-позиции по
каноническим IDs текущего connection, а frontend запрашивает новую revision Weekly Review и
инвалидирует связанные caches. Если дополнительное формирование отчёта не удалось, категория
остаётся сохранённой, а интерфейс показывает отдельное предупреждение.

`ZERO_UNEXPECTED` показывается с severity `INFO`, не блокирует readiness и оставляет GP числом.
Текст обязан говорить о возможной недостоверности прибыли; действие — проверить источник и при
необходимости пересинхронизировать. Информационное событие входит в открытый счётчик, но само по
себе не переводит магазин из `OK` в `WARNING`.
