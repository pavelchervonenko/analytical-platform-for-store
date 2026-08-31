---
doc_schema: 1
doc_type: decision
status: proposed
owner: product
audience:
  - developer
  - manager
decision_date: null
implementation_status: not-started
decision_sources:
  - docs/current/product/periods.md
  - frontend/src/dashboard/OverviewPage.tsx
  - frontend/src/dashboard/OverviewManagementSections.tsx
implementation_sources:
  - frontend/src/dashboard/OverviewPage.tsx
  - frontend/src/dashboard/OverviewManagementSections.tsx
verification_sources:
  - frontend/src/dashboard/OverviewPage.test.tsx
required_reviewers:
  - product
  - frontend
  - backend
supersedes: []
superseded_by: null
---

# ADR-0002: Единый период внутри показателей главной

## Контекст

Overview получает KPI/category/attach/employee за selected `start..end`, а plan/quality — за
`month-01..asOf`. Commercial card показывает selected amount/quantity, но предпочитает monthly
`actualSharePercent`, gap и target. Week/custom объединяет разные знаменатели.

## Предлагаемое решение

Разделить два слоя:

1. «Результаты выбранного периода»: amount, quantity и share имеют один selected диапазон и один
   store denominator, без month plan gap.
2. «План месяца»: target, completion, pace, forecast и remaining имеют только
   `month-01..asOf` и явную подпись месяца/`asOf`.

В одной карточке запрещено смешивать эти scope. Если план показывается рядом с недельным фактом,
это отдельный визуальный блок. Frontend не определяет новое правило округлением; selected share
приходит из backend либо вычисляется только по согласованному same-period contract и тесту.

## Текущее поведение

`OverviewPage` передаёт selected KPI/categories и monthly plan в `ManagementSummary`;
`CommercialMetric` берёт monthly share при наличии plan. Решение не реализовано. До него week/custom
commercial cards не используются как точная оценка доли к плану.

## Условия вступления в силу

1. Утвердить layout двух scope.
2. Определить authoritative selected share transport.
3. Убрать month fields из selected-result cards, оставить их в plan block.
4. Добавить tests day/week/month/custom, month boundaries, null/zero/negative revenue, no plan,
   incomplete classification и timezone.
5. Добавить query test одинакового `start/end` внутри result card.
6. Выполнить local visual review desktop/tablet/mobile по `AGENTS.md`.

## Альтернативы

1. Только month Overview — теряется week/custom.
2. Пропорциональный недельный plan — создаёт несуществующую методику.
3. Tooltip поверх текущего mix — не исправляет знаменатели.

## Последствия и проверка

Плюс — каждая цифра имеет один период. Риск — меняется layout/transport и snapshots tests. Gate:
component/query tests и visual review всех period modes; один month mode недостаточен.

Связанные контракты: [periods](../current/product/periods.md),
[frontend scope](../current/frontend/period-and-scope-contract.md),
[overview](../current/frontend/overview.md).
