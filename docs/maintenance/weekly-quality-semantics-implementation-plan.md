---
doc_schema: 1
doc_type: working
status: closed
owner: project
audience:
  - developer
  - product
  - operator
created_at: 2026-09-20
review_by: 2026-10-04
source_material:
  - docs/current/product/data-quality.md
  - docs/current/ai/weekly-review.md
  - backend/src/main/java/com/storeanalytics/quality/repository/PeriodQualityIssueRepository.java
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
required_reviewers:
  - backend
  - product
  - ai-semantic
  - operations
exit_target: current
---

# Семантика quality-событий для Weekly Review

Статус: реализация и итоговая проверка завершены 2026-09-20.

## Цель и границы

План отделяет диагностические особенности ведения LiveSklad от дефектов, которые действительно
делают недельную чистую выручку недостоверной. Сами события не скрываются и не удаляются: меняется
только их влияние на Weekly Review, корректируется ложная интерпретация двух типов исходных
документов и понижается severity нулевой себестоимости.

Изменение не переписывает опубликованные immutable weekly snapshots. Новая семантика применяется
при формировании следующей revision после релиза и отдельного операторского запуска.

## Обязательные инварианты

1. `SALE_PAYMENT_MISMATCH` и `RETURN_PAYMENT_MISMATCH` остаются открытыми и видимыми в качестве
   диагностики источника, но не ограничивают `NET_REVENUE` в Weekly Review.
2. `RETURN_CASH_TRANSACTION_MISMATCH` не ограничивает Weekly Review только при наличии точного
   доказательства: сумма активных оплат нормализованного документа равна сумме
   `detail.cash.money + detail.cash.bank + detail.cash.invoice` последней сохранённой source-version.
3. Отсутствующая, повреждённая или неоднозначная source-version не трактуется как подтверждение:
   `RETURN_CASH_TRANSACTION_MISMATCH` продолжает ограничивать отчёт (fail closed).
4. Активная запись return-feed с `detail.sourceType=sale` и уже существующей активной продажей того
   же connection/store считается повторным представлением продажи: документ, позиции, оплаты и
   атрибуция не меняются, ложный `RETURN_ORIGINAL_DOCUMENT_MISSING` закрывается.
5. Если точная продажа того же магазина не найдена, прежнее fail-closed поведение сохраняется:
   событие остаётся открытым, документ учитывается как unresolved и source-version помечается
   skipped.
6. Миграция закрывает только доказуемые stale false-positive события из пункта 4 по общему
   предикату; номера конкретных production-документов в миграцию не зашиваются.
7. `ZERO_UNEXPECTED_COST` и `RETURN_ZERO_UNEXPECTED_COST` остаются открытыми, видимыми и
   учитываются диагностическими счётчиками, но имеют severity `INFO`. Финансовое значение cost=0
   и существующее отсутствие weekly limitation сохраняются.
8. Изменение severity применяется и к новым событиям, и к уже открытым событиям миграцией.
9. Никакие production-данные, snapshots или AI jobs не меняются в рамках разработки и тестов.

## Порядок реализации и review-gates

1. Зафиксировать настоящий контракт и исходные тестовые ожидания. Review: документационная
   целостность, отсутствие production-identifiers и соответствие согласованным четырём решениям.
2. Исключить `SALE_PAYMENT_MISMATCH` и `RETURN_PAYMENT_MISMATCH` из period consistency count.
   Review: интеграционный тест доказывает сохранение OPEN-событий и нулевое влияние на weekly
   consistency.
3. Добавить условное доказательство для `RETURN_CASH_TRANSACTION_MISMATCH`. Review: позитивный
   сценарий с равными app/source totals и негативные сценарии mismatch, missing и malformed raw.
4. Исправить обработку sale-as-return и добавить безопасную миграцию stale issues. Review:
   same-store существующая продажа не мутирует; foreign/missing/return document не ослабляют guard;
   миграция идемпотентна и действует только по доказуемому предикату.
5. Перевести unexpected zero-cost в `INFO`. Review: все producers и существующие OPEN rows
   согласованы, видимость и diagnostic counters сохраняются, weekly readiness не меняется.
6. Обновить current API/product/AI contracts и runbook, выполнить backend/frontend/documentation
   suites и локальную визуальную проверку раздела качества данных на desktop/tablet/mobile.
7. Выполнить итоговый review полного diff и закрыть план только при подтверждении каждого
   инварианта тестом либо явно зафиксированным ограничением.

## Матрица приёмки

| Сценарий | Ожидаемый результат |
|---|---|
| Sale payment отличается от net | Событие видно; `NET_REVENUE` не ограничен |
| Return payment отличается от net/items | Событие видно; `NET_REVENUE` не ограничен |
| Return cash journal отличается, app payments равны latest `detail.cash` | Событие видно; `NET_REVENUE` не ограничен |
| App payments не равны latest `detail.cash` | `NET_REVENUE` ограничен |
| Latest raw отсутствует или cash неразбираем | `NET_REVENUE` ограничен без ошибки SQL |
| Return-feed прислал существующую same-store sale | Sale неизменна; ложное событие закрыто |
| Return-feed sale не найден или принадлежит другому store | Fail closed; событие остаётся OPEN |
| Новый unexpected zero cost | OPEN + `INFO`, виден в реестре, не ограничивает weekly KPI |
| Старый OPEN unexpected zero cost | После миграции `INFO`, остальные поля не меняются |

## Критерий закрытия

Все четыре решения подтверждены unit/integration/migration тестами; current-контракты описывают
реализованное поведение; локальный visual review не выявляет регрессий INFO-представления; полный
diff-review не обнаруживает скрытия событий, ослабления store boundary или fail-open обработки
недоказанного cash-соответствия.

## Результаты review-gates

1. Контракт зафиксирован до изменения кода; production identifiers не попали в миграции или
   runtime-правила, а план зарегистрирован в документационном inventory.
2. Payment mismatch события остаются `OPEN` и видимыми, но интеграционный тест подтверждает их
   нулевое влияние на period consistency и weekly `NET_REVENUE`.
3. Cash mismatch исключается только при точном равенстве active app payments и latest retained
   `detail.cash`. Mismatch, missing и malformed evidence проверены как fail closed.
4. Sale-typed return-feed сохраняет существующую active same-store продажу без изменений и
   закрывает stale missing-original issue. Missing/deleted/foreign-store/latest-return случаи
   остаются `OPEN`; V53 проверена на точность и идемпотентность.
5. Все producers создают unexpected zero-cost как `INFO`, V54 меняет только существующие `OPEN`
   события, detail API сохраняет видимость, а readiness и cost=0 semantics не меняются.
6. Quality policy повышена до `weekly-quality-v8`; опубликованные snapshots не переписываются.
   Current-контракты и AI runbook обновлены для формирования новой revision после релиза.
7. Полный backend `check` завершён без ошибок: 1172 теста. Frontend `check` завершён без ошибок:
   58 test files и 273 tests, lint, contracts и production build. Documentation unit suite и
   strict checker зелёные. Локальный fixture-based visual review `/quality` прошёл на desktop,
   tablet и mobile; INFO-строка и detail dialog просмотрены без переполнения и наложений.

Итоговый code review не выявил незакрытых замечаний. Production, существующие snapshots и AI jobs
в рамках этой реализации не изменялись.
