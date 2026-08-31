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
superseded_by: null
---

# ADR-0001: Атрибуция возврата сотруднику строки возврата

## Контекст

Реализация назначает возврат сотруднику исходной продажи; integration test закрепляет это.
Последняя методика заказчика требует сотрудника строки/документа возврата. Store/category totals
не меняются, но employee revenue, GP, mix, rating и employee attach-rate меняются.

Поле сотрудника реального LiveSklad return payload ещё не подтверждено датированным sanitized
evidence, поэтому решение остаётся `proposed`.

## Предлагаемое решение

После подтверждения provider contract использовать сотрудника самого возврата. До изменения
атрибуции сохранить отдельно оба normalized provenance: processing employee возврата и employee
исходной продажи, а также связь с original document. Одного retained raw payload недостаточно для
аудита, retention и перерасчёта. Не использовать исходного сотрудника как скрытый fallback:
отсутствующий/unresolved processing employee относится к «Не назначен» и создаёт quality issue.

Store/category signed totals не меняются. Employee facts/snapshots пересчитываются только в
согласованном историческом диапазоне с новой revision/provenance.

## Текущее поведение

`ReturnSyncPersistence` выбирает employee исходной продажи. До реализации employee-level
показатели периода с возвратами не полностью авторитетны; store reconciliation не доказывает
правильного ответственного.

## Условия вступления в силу

1. Получить sanitized payload обоих типов возврата и подтвердить employee identifier/missing case.
2. Изменить parser/persistence/quality без расширения хранения PII.
3. Добавить normalized provenance без расширения PII: original employee, processing employee и
   выбранное attribution rule/version.
4. Добавить tests: два разных resolved employee, missing, duplicate, orphan и late link.
5. Определить historical recomputation и snapshot revision policy.
6. Сверить store/category invariants и employee distribution с CRM.
7. Провести canary обоих магазинов и обновить `implementation_status` до `verified`.

## Альтернативы

1. Оставить employee исходной продажи — реализовано, но противоречит методике.
2. Fallback на исходного employee — скрывает смешение двух правил.
3. Две финансовые атрибуции — повышают сложность; provenance достаточно хранить отдельно.

## Последствия и проверка

Плюс — ответственность совпадает с CRM return. Риск — историческое распределение рейтинга и
employee attach изменится; provider employee может быть неполным. Решение доказано только после
code/tests/reconciliation/canary, не по одному store total.

Связанные контракты: [возвраты](../current/product/sales-and-returns.md),
[сотрудники](../current/product/employees-and-rating.md),
[attach-rate](../current/product/attach-rate.md).
