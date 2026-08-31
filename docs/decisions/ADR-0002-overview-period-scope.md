---
doc_schema: 1
doc_type: decision
status: accepted
owner: product
audience:
  - developer
  - manager
decision_date: 2026-08-31
implementation_status: implemented
decision_sources:
  - docs/current/product/periods.md
  - docs/current/product/plans-and-shifts.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/OverviewMetricsService.java
  - frontend/src/dashboard/OverviewPage.tsx
  - frontend/src/dashboard/OverviewManagementSections.tsx
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/service/OverviewMetricsServiceTest.java
  - frontend/src/dashboard/OverviewPage.test.tsx
required_reviewers:
  - product
  - frontend
  - backend
supersedes: []
superseded_by: null
---

# ADR-0002: Период и cohort показателей главной

## Контекст

Раньше week/custom amount приходил за выбранный период, а share/gap мог браться из месячного
плана. Кроме того, верхние карточки всегда показывали STORE, хотя заказчику нужен управленческий
режим только по продавцам.

## Решение

1. Amount, quantity и share верхних карточек используют один selected `start..end` и один scope.
2. `SELLERS` («Только продавцы») — режим по умолчанию; `STORE` («Весь магазин») включается
   переключателем внутри тёмного блока и сохраняется в URL.
3. Месячный план остаётся одним. В `SELLERS` он применяется к факту продавцов, в `STORE` — к факту
   всего магазина.
4. В month mode месячный target/gap может быть рядом с фактом. В week/custom он остаётся только в
   отдельном блоке «План месяца» и не подменяет selected share.
5. Store structure и attach-map сохраняют STORE semantics и получают явную подпись.

## Текущее реализованное поведение

Backend отдаёт `overview-metrics-v1` с authoritative selected share и reconciliation controls.
Frontend передаёт один scope и в selected metrics, и в month plan. Неизвестный/отсутствующий
`overviewScope` трактуется как `SELLERS`; plan transport default остаётся `STORE` для совместимости
других потребителей.

## Условия вступления решения в силу

Код и component tests реализованы. Для статуса `verified` требуется обязательный local visual
review desktop/tablet/mobile по `AGENTS.md` и успешный полный CI.

## Альтернативы

1. Только month Overview — отклонено: теряется week/custom анализ.
2. Пропорциональный недельный plan — отклонено: такой методики заказчик не задавал.
3. Два разных плана для scope — отклонено: согласован один месячный план.

## Последствия и проверка

Каждая верхняя цифра теперь имеет один период и cohort. Переключение может менять и фактические
карточки, и месячный прогресс, но не само значение плана. Gate: backend reconciliation tests,
frontend period/scope tests и visual review всех размеров.

Связанные контракты: [periods](../current/product/periods.md),
[frontend scope](../current/frontend/period-and-scope-contract.md),
[overview](../current/frontend/overview.md).
