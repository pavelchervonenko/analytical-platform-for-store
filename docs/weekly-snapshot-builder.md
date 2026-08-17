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

Backend фиксирует числа, достаточность выборки и допустимые evidence references. LLM не вычисляет
сравнения, лидеров или mentor/learner-пары. Backend формирует ограниченный набор candidate signals;
модель выбирает формулировку и может связывать только подтверждённые сигналы.

## Активные версии и правила

- facts schema: `1`;
- metric contract: `weekly-metrics-v3`;
- calculation: `weekly-snapshot-v6`;
- quality policy: `weekly-quality-v3`.

Активный `WeeklySnapshotPolicyV3` использует unit-based `attach-rate-v3`
и добавляет versioned candidate policy. Порог нагрузки: хотя бы одна смена и
или менее 12 часов дают `LIMITED`. Структура продаж: меньше 3 завершённых чеков — `INSUFFICIENT`,
3–5 — `LIMITED`. Attach: меньше 3 единиц базы — `INSUFFICIENT`, 3–4 — `LIMITED`.
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

## Deterministic analytical candidates

Candidate создаётся только при доступных evidence и фиксированной достаточности:

- store revenue/profit: относительное изменение не менее 5%;
- employee revenue/efficiency: относительное изменение не менее 10%;
- category movement: не менее 15% при доле категории не менее 3% хотя бы в одном периоде; максимум
  два роста и два снижения, выбранные по абсолютному вкладу;
- plan gap: отклонение projected completion от 100% не менее чем на 5 п.п.;
- attach gap: изменение не менее 5 на сто чеков и denominator не меньше 5 в обоих периодах;
- employee additional share: изменение не менее 3 п.п.; rating score — не менее 5 пунктов;
- отрицательные category/store значения и нулевая предыдущая база не получают оценку динамики.

Team benchmark строится минимум по трём `SUFFICIENT` сотрудникам. Backend сохраняет Q1, медиану и
Q3; квартили считаются nearest-rank, медиана чётной выборки — среднее двух центральных значений.
Единственный лидер допустим только при преимуществе минимум 5% над вторым значением. Для category
benchmark берутся максимум три крупнейшие категории. Learner находится ниже медианы; в один
candidate входят максимум три learner. Most improved требует минимум три сопоставимых сотрудника.

Каждый candidate хранит scope metadata, sufficiency и полный набор evidence refs. Semantic validator
сверяет insight с candidate по kind/theme/scope/evidence, а team relationship допускает только
точную backend-owned пару.

## Реализованные проекции

- результат магазина, валовая прибыль и маржа при доступной себестоимости;
- средний чек и дополнительные продажи на телефон;
- продажи и доли по аналитическим группам и реальным категориям;
- store и employee attach-rate с sample sufficiency;
- план магазина: используется plan context с максимальной `asOfDate`, то есть актуальный контекст на
  конец отчётной недели; оба исходных контекста пограничной недели остаются в source;
- нагрузка, результат, эффективность, структура продаж, backend rating и категории сотрудников;
- количество сотрудников, пригодных для team benchmark;
- material store/category/plan/attach movements и employee self-dynamics;
- team Q1/median/Q3, unique leaders, most improved и mentor/learner candidates.

Планы не пересчитываются в interpretation-модуле: builder переносит готовые значения
`StorePlanProgressService`.

## Следующая граница

Persistence adapter для `analytics_snapshots` и `analytics_snapshot_employees` уже реализован и
описан в `weekly-snapshot-persistence.md`.

1. Snapshot job/revision orchestration, lease/retry и сравнение с предыдущей ревизией.
2. Token preflight и окончательная калибровка category/materiality limits на staging-данных.
3. Semantic validator результата LLM.
4. Только после этого — YandexGPT HTTP adapter и publication workflow.
