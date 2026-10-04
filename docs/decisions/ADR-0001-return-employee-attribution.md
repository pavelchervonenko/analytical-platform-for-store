---
doc_schema: 1
doc_type: decision
status: superseded
owner: product
audience:
  - developer
  - manager
decision_date: 2026-08-31
implementation_status: implemented
decision_sources:
  - docs/current/product/sales-and-returns.md
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
implementation_sources:
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
required_reviewers:
  - product
  - integrations
  - backend
supersedes: []
superseded_by: docs/decisions/ADR-0006-livesklad-return-employee-analytics.md
---

# ADR-0001: Атрибуция возврата сотруднику исходной продажи

Решение заменено для аналитических показателей [ADR-0006](ADR-0006-livesklad-return-employee-analytics.md).
Текст ниже сохраняет прежнее решение и реализованное поведение; новое правило пока требует
отдельной реализации и проверки перехода, а не считается уже применённым к production.

## Контекст

Возврат может содержать сотрудника, который оформил операцию, и ссылку на исходную продажу с
другим продавцом. Для рейтинга и финансовых показателей требуется однозначно определить, кому
уменьшать выручку, количество, себестоимость и допродажи.

## Решение

Финансовый факт возврата относится к сотруднику исходной продажи. Processing employee возврата не
используется ни как основное значение, ни как fallback. Если исходная продажа ещё не найдена,
возврат сохраняется без `employee_id` и временно входит в «Не назначен». После late link он получает
сотрудника оригинала.

## Текущее реализованное поведение

`ReturnSyncPersistence` выбирает только `original.map(SalesDocument::getEmployee)`.
Store/category signed totals от выбора сотрудника не меняются. Денежные Employee KPI, GP, mix
и финансовые составляющие rating уменьшаются у продавца исходной продажи. Для аналитического
attach v4 применяется уточнение [ADR-0003](ADR-0003-warranty-attach-attribution.md); финансовая
атрибуция этого решения сохраняется.

## Условия вступления решения в силу

Решение реализовано в normalization. Для статуса `verified` требуется успешный CI/integration run
`ReturnSyncIntegrationTest` после изменения: два разных resolved employee, orphan и late link.

## Альтернативы

1. Использовать обработчика возврата — отклонено: он не является владельцем исходной продажи.
2. Fallback на обработчика у orphan return — отклонено: создаёт временно неверный рейтинг.
3. Хранить две финансовые атрибуции — отклонено: финансовый ответственный должен быть один.

## Последствия и проверка

Плюс — возвраты симметрично уменьшают показатели продавца продажи. Orphan return до late link
виден в reconciliation как «Не назначен», а не скрыто приписан другому сотруднику. Проверка должна
подтверждать неизменность store/category totals и перераспределение employee facts.

Связанные контракты: [возвраты](../current/product/sales-and-returns.md),
[сотрудники](../current/product/employees-and-rating.md),
[бизнес-показатели](../current/product/business-metrics.md).
