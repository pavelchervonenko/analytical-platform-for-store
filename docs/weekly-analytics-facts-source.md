# Weekly Analytics Facts Source

Статус: реализовано и проверено 2026-08-01. Это внутренний backend-контракт подготовки данных до
построения immutable snapshot и до любого обращения к LLM.

## Назначение

`WeeklyAnalyticsFactsSource` собирает согласованный типизированный срез уже рассчитанных backend KPI.
Он не пересчитывает формулы, не формирует narrative, не сохраняет snapshot и не вызывает YandexGPT.

Запрос `WeeklyAnalyticsFactsQuery` содержит магазин, целевую неделю и сравнительную неделю.
Инварианты:

- обе недели строго Monday–Sunday;
- каждый период содержит семь календарных дней;
- comparison period является непосредственно предыдущей полной неделей;
- один вызов относится ровно к одному магазину.

## Консистентность чтения

`BackendWeeklyAnalyticsFactsSource.load` выполняется в read-only транзакции PostgreSQL с уровнем
`REPEATABLE_READ`. Все KPI внутри вызова видят один согласованный database snapshot, даже если
следующая синхронизация началась параллельно.

Typed result содержит:

- current и previous `StoreKpiResult`;
- current и previous `CategoryKpiResult`;
- current и previous `AttachRateResult`;
- current и previous `EmployeeKpiResult`;
- current и previous `EmployeeCategoryKpiResult`;
- current и previous `EmployeeRatingResult`;
- `AverageKpiResult`, который уже содержит current/previous comparisons;
- `StoreDataStatusView` для будущего source-quality gate;
- существующие plan contexts для каждого месяца, которого касается целевая неделя.

Если неделя пересекает границу месяцев, источник запрашивает два независимых plan context:
предыдущий месяц по его последнему дню и новый месяц по воскресенью целевой недели. Отсутствующий
план остаётся отсутствующим и не заменяется нулями.

## Граница персональных данных

На этом внутреннем слое ещё допустимы employee UUID и display name, потому что они нужны для
membership snapshot и отображения в защищённом кабинете. Следующий input-manifest builder обязан:

1. назначить стабильные внутри snapshot псевдонимы `E01...E10`;
2. сохранить соответствие UUID/display name только в `analytics_snapshot_employees`;
3. удалить display name, UUID и unassigned pseudo-employee из provider payload;
4. передать unassigned totals только как store-level quality/context facts.

## Следующий реализованный слой

`WeeklySnapshotDraftBuilder` теперь реализует versioned sample-sufficiency/quality policy,
псевдонимизацию, атомарные facts/evidenceRef, manifest и canonical hash. Подробный контракт описан в
`docs/weekly-snapshot-builder.md`.

Нижележащие persistence snapshot/job, provider projection, token/cost preflight, YandexGPT call,
structural/fact/safety validation и publication workflow теперь реализованы. Этот документ остаётся
контрактом только для первого, backend-owned слоя сбора фактов; полный lifecycle описан в
`llm-analysis-lifecycle.md`.
