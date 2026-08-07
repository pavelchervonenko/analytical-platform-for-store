# Weekly Snapshot Builder

Статус: active builder актуализирован 2026-08-07. Он детерминированно формирует payload для
сохраняемого snapshot; persistence/job lifecycle и YandexGPT worker реализованы отдельными слоями.

## Граница ответственности

`WeeklySnapshotDraftBuilder` получает один согласованный `WeeklyAnalyticsFacts` и формирует:

- quality status `READY`, `PARTIAL` или `BLOCKED`;
- псевдонимы сотрудников `E01...E10` и отдельный membership с UUID/именем;
- атомарные store, team и employee facts с current/previous/delta;
- evidence manifest и data limitations;
- canonical SHA-256 для обнаружения реального изменения snapshot;
- provider-neutral payload, соответствующий `WeeklyInterpretationInput v1`.

Backend фиксирует числа, достаточность выборки и допустимые evidence references. Он не выбирает за
LLM сильную сторону, слабую сторону, риск или рекомендацию. Candidate signals пока намеренно пусты:
это не сужает интерпретацию до набора заранее запрограммированных выводов.

## Активные версии и правила

- facts schema: `1`;
- metric contract: `weekly-metrics-v2`;
- calculation: `weekly-snapshot-v5`;
- quality policy: `weekly-quality-v2`.

Активный `WeeklySnapshotPolicyV2` добавляет явный sample завершённых продаж и переносит
receipt-based attach-rate v2. Порог нагрузки: хотя бы одна смена и положительные часы; одна смена
или менее 12 часов дают `LIMITED`. Структура продаж: меньше 3 завершённых чеков — `INSUFFICIENT`,
3–5 — `LIMITED`. Attach: меньше 3 релевантных чеков — `INSUFFICIENT`, 3–4 — `LIMITED`.
Покрытие рейтинга ниже 50% блокирует общий анализ, 50–74% ограничивает его. Для team benchmark
нужны минимум три достаточных сотрудника, для единственного лидера — преимущество не менее 5%.
Изменение порогов требует новой versioned policy, а не скрытой правки prompt.

`BLOCKED` применяется, когда источник имеет `NOT_SYNCED`/`ERROR` либо данные не покрывают воскресенье
целевой недели. Локальные проблемы себестоимости, классификации или attach-rate дают `PARTIAL` и
точечные limitations; достоверные revenue/quantity facts при этом не удаляются.

## Приватность и воспроизводимость

Псевдонимы назначаются сортировкой UUID, поэтому порядок строк из repository не влияет на результат.
UUID и display name остаются только в `analytics_snapshot_employees`; provider payload содержит лишь
`E01...E10`. Unassigned pseudo-employee не становится участником employee analysis.

Hash считается по canonical payload и membership. Это важно: замена сотрудника при тех же числах не
может ошибочно считаться `UNCHANGED`. Provider получает только непрозрачный hash, но не hash material.

Все facts и evidence сортируются. При превышении schema limits builder завершает job ошибкой, а не
молча обрезает сотрудников или категории.

## Реализованные проекции

- результат магазина, валовая прибыль и маржа при доступной себестоимости;
- средний чек и дополнительные продажи на телефон;
- продажи и доли по аналитическим группам и реальным категориям;
- store и employee attach-rate с sample sufficiency;
- план магазина: используется plan context с максимальной `asOfDate`, то есть актуальный контекст на
  конец отчётной недели; оба исходных контекста пограничной недели остаются в source;
- нагрузка, результат, эффективность, структура продаж, backend rating и категории сотрудников;
- количество сотрудников, пригодных для team benchmark.

Планы не пересчитываются в interpretation-модуле: builder переносит готовые значения
`StorePlanProgressService`.

## Следующая граница

Persistence adapter для `analytics_snapshots` и `analytics_snapshot_employees` уже реализован и
описан в `weekly-snapshot-persistence.md`.

1. Snapshot job/revision orchestration, lease/retry и сравнение с предыдущей ревизией.
2. Token preflight и окончательная калибровка category/materiality limits на staging-данных.
3. Semantic validator результата LLM.
4. Только после этого — YandexGPT HTTP adapter и publication workflow.
