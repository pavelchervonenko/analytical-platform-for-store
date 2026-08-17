# Каталог фактов LLM v1

Статус: DRAFT 2026-08-01. Core store/category/plan/employee facts, evidence manifest, quality и
sufficiency policy реализованы в weekly snapshot draft builder. Каталог остаётся DRAFT до
staging-калибровки materiality/trend/token limits. Формулы остаются в существующих backend-модулях;
interpretation только собирает их результаты и не пересчитывает KPI.

Implementation note: `EmployeeCategoryKpiProjection`, deterministic snapshot draft builder,
durable job/snapshot lifecycle и YandexGPT provider worker реализованы. Каталог сохраняет статус
DRAFT только до staging-калибровки materiality/trend/token limits на обезличенных примерах.

## 1. Назначение

Каталог является allowlist для:

- построения immutable weekly analytics snapshot;
- формирования `WeeklyInterpretationInput v1`;
- semantic validation всех `evidenceRefs`;
- backend rendering подтверждённых чисел для dashboard и Telegram;
- evaluation-набора и проверки совместимости новых версий prompt/schema.

Факт включается в LLM input только после source quality gate и sample-sufficiency проверки.
Отсутствующее значение не заменяется нулём, кроме случаев, где ноль является подтверждённым
результатом исходного KPI-контракта.

## 2. Формат evidenceRef

Общие правила:

```text
STORE.<METRIC>.<ASPECT>
STORE.GROUP:<GROUP_CODE>.<METRIC>.<ASPECT>
STORE.CATEGORY:<CATEGORY_CODE>.<METRIC>.<ASPECT>
STORE.ATTACH:<METRIC_CODE>.<METRIC>.<ASPECT>
STORE.PLAN:<DIRECTION_CODE>.<METRIC>
EMP:<EMPLOYEE_REF>.<METRIC>.<ASPECT>
EMP:<EMPLOYEE_REF>.GROUP:<GROUP_CODE>.<METRIC>.<ASPECT>
EMP:<EMPLOYEE_REF>.CATEGORY:<CATEGORY_CODE>.<METRIC>.<ASPECT>
EMP:<EMPLOYEE_REF>.ATTACH:<METRIC_CODE>.<METRIC>.<ASPECT>
TEAM.<METRIC>
TEAM.GROUP:<GROUP_CODE>.<METRIC>
TEAM.COMPETENCY:<COMPETENCY_CODE>.<METRIC>
```

`ASPECT`:

- `CURRENT` — значение целевой недели;
- `PREVIOUS` — значение предыдущей полной недели;
- `DELTA` — абсолютное изменение current minus previous;
- `DELTA_PERCENT` — относительное изменение, только если предыдущая база допустима;
- `TREND` — backend-код направления по дневному ряду, не рассчитанный LLM;
- `MEDIAN` — медиана допустимой выборки команды;
- `DISTRIBUTION` — backend-owned summary распределения без ФИО.

Каждый evidenceRef разрешается ровно в один versioned fact. Display name не входит ни в ссылку, ни
в provider input. Связь `employeeRef -> employeeId/displayName` хранится только в snapshot
membership.

## 3. Единицы

Допустимые единицы входного контракта:

| Unit | Значение |
| --- | --- |
| `MONEY` | денежная сумма в рублях |
| `COUNT` | количество документов, строк, смен или сотрудников |
| `PERCENT` | процент или изменение в процентных пунктах с явным metricCode |
| `RATE_PER_HUNDRED` | доля релевантных SALE-чеков с допродажей, процентов |
| `HOURS` | фактически отработанные часы |
| `SCORE` | backend rating score |
| `RANK` | backend-assigned rank |
| `STATUS` | значение закрытого backend enum |

LLM не выполняет арифметику над этими значениями. Для `DELTA_PERCENT` и долей backend сохраняет
исходные числитель/знаменатель либо provenance рассчитанного KPI.

## 4. Аналитические группы

`CATEGORY:<code>` используется только для реального `analytics_categories.code`.
Агрегаты используют `GROUP:<code>`:

| Group code | Backend-правило |
| --- | --- |
| `PHONES` | `countsAsPhone=true` |
| `DEVICES` | `countsAsDevice=true`; включает PHONES |
| `ACCESSORY` | `categoryKind=ACCESSORY` |
| `SERVICE` | `categoryKind in SERVICE/WARRANTY/PROTECTION` |
| `ADDITIONAL_REVENUE` | `countsAsAdditionalRevenue=true` |

Группы пересекаются и не суммируются. `UNMAPPED` остаётся категорией и не входит ни в одну
аналитическую группу. `EXCLUDE` не попадает в projection или snapshot.

## 5. Store facts

### 5.1 Финансовый результат

Источник: `StoreKpiService`, formula `store-kpi-v1`.

Для `NET_REVENUE`, `NET_QUANTITY`, `COST_AMOUNT`, `GROSS_PROFIT`,
`MARGIN_PERCENT` допускаются `CURRENT`, `PREVIOUS`, `DELTA` и при допустимой базе
`DELTA_PERCENT`. Для маржи `DELTA` означает процентные пункты; `DELTA_PERCENT` для маржи не
создаётся.

Примеры:

```text
STORE.NET_REVENUE.CURRENT
STORE.NET_REVENUE.DELTA
STORE.GROSS_PROFIT.CURRENT
STORE.MARGIN_PERCENT.DELTA
```

Cost/gross-profit/margin facts отсутствуют для затронутого scope при неполной себестоимости.
Качество не распространяется на доступные revenue/quantity facts.

### 5.2 Категории и группы

Источники: `CategoryKpiService`, `EmployeeCategoryKpiProjection`;
`category-kpi-v1` и `employee-category-kpi-v1`.

Метрики:

- `NET_REVENUE`;
- `NET_QUANTITY`;
- `COST_AMOUNT`;
- `GROSS_PROFIT`;
- `MARGIN_PERCENT`;
- `REVENUE_SHARE_PERCENT`;
- `CONTRIBUTION_TO_REVENUE_DELTA`;
- `CONTRIBUTION_TO_GROSS_PROFIT_DELTA`.

Примеры:

```text
STORE.GROUP:PHONES.NET_REVENUE.DELTA
STORE.GROUP:SERVICE.REVENUE_SHARE_PERCENT.CURRENT
STORE.CATEGORY:CHARGER_CABLE.MARGIN_PERCENT.CURRENT
```

Contribution рассчитывает snapshot builder из уже агрегированных current/previous facts и
сохраняет calculation version. LLM его не рассчитывает.

### 5.3 Средние показатели

Источник: `AverageKpiService`, formula `average-kpi-v1`.

```text
STORE.AVERAGE_RECEIPT.CURRENT
STORE.AVERAGE_RECEIPT.DELTA_PERCENT
STORE.ADDITIONAL_REVENUE_PER_PHONE.CURRENT
STORE.CATEGORY:<CODE>.AVERAGE_UNIT_PRICE.CURRENT
```

Значения с неположительным знаменателем и их динамика отсутствуют.

### 5.4 Attach-rate

Источник: `AttachRateService`, formula `attach-rate-v3`. Используются чистые количества единиц:
продажи минус возвраты; совместный чек устройства и допа не требуется.

Для каждого разрешённого `metricCode`:

```text
STORE.ATTACH:<METRIC_CODE>.NUMERATOR_QUANTITY.CURRENT
STORE.ATTACH:<METRIC_CODE>.DENOMINATOR_QUANTITY.CURRENT
STORE.ATTACH:<METRIC_CODE>.RATE_PER_HUNDRED.CURRENT
STORE.ATTACH:<METRIC_CODE>.RATE_PER_HUNDRED.DELTA
```

Rate может превышать 100; отрицательный числитель даёт rate 0. Неположительный denominator блокирует rate, но не
скрывает сам denominator и соответствующую data limitation.

### 5.5 План магазина

Источник: `StorePlanProgressService`, formula `store-plan-progress-v1`.

Для каждого `REVENUE/ACCESSORY/SERVICE/ADDITIONAL`:

```text
STORE.PLAN:<DIRECTION>.ACTUAL_AMOUNT
STORE.PLAN:<DIRECTION>.TARGET_AMOUNT
STORE.PLAN:<DIRECTION>.AMOUNT_COMPLETION_PERCENT
STORE.PLAN:<DIRECTION>.CURRENT_DAILY_PACE
STORE.PLAN:<DIRECTION>.PROJECTED_AMOUNT
STORE.PLAN:<DIRECTION>.PROJECTED_COMPLETION_PERCENT
STORE.PLAN:<DIRECTION>.REMAINING_AMOUNT
STORE.PLAN:<DIRECTION>.REQUIRED_PER_REMAINING_DAY
STORE.PLAN:<DIRECTION>.ACTUAL_SHARE_PERCENT
STORE.PLAN:<DIRECTION>.TARGET_SHARE_PERCENT
STORE.PLAN:<DIRECTION>.SHARE_GAP_PERCENTAGE_POINTS
STORE.PLAN:<DIRECTION>.CRITERION_COMPLETION_PERCENT
STORE.PLAN:<DIRECTION>.STATUS
```

Персональных plan facts не создаётся.

### 5.6 Качество и покрытие

Источники: `StorePeriodQualityService`, store/category/attach KPI data quality и data freshness.

Коды quality issues сохраняются как backend limitations, а не пересказываются моделью. Числовые
счётчики могут использовать refs вида `STORE.QUALITY:<ISSUE_CODE>.AFFECTED_COUNT`.
`readyForDecisions=false` само по себе не всегда блокирует weekly analysis: snapshot quality policy
маппит состояние в READY/PARTIAL/BLOCKED и фиксирует `qualityPolicyVersion`.

## 6. Employee facts

### 6.1 Участие и нагрузка

Источники: `EmployeeRatingService`, work schedule и employee assignment.

```text
EMP:<REF>.WORKLOAD.SHIFT_COUNT.CURRENT
EMP:<REF>.WORKLOAD.WORKED_HOURS.CURRENT
EMP:<REF>.WORKLOAD.STATUS
EMP:<REF>.WORKLOAD.SUFFICIENCY
EMP:<REF>.RATING_ELIGIBILITY.STATUS
```

Нулевые смены означают отсутствие достаточной выборки, а не слабый результат.

### 6.2 Финансы и эффективность

Источники: `EmployeeKpiService` и `EmployeeRatingService`.

```text
EMP:<REF>.NET_REVENUE.CURRENT
EMP:<REF>.NET_REVENUE.DELTA
EMP:<REF>.STORE_REVENUE_SHARE_PERCENT.CURRENT
EMP:<REF>.REVENUE_PER_SHIFT.CURRENT
EMP:<REF>.REVENUE_PER_HOUR.CURRENT
EMP:<REF>.REVENUE_PER_HOUR.DELTA
```

Revenue per hour/shift передаётся только при достаточной рабочей нагрузке. Низкая абсолютная
выручка без контекста часов не становится deterministic weakness.

### 6.3 Категории, группы и дополнительные продажи

Источник: обязательная `EmployeeCategoryKpiProjection`.

Для каждой категории и группы доступны те же financial/share facts, что в store scope:

```text
EMP:<REF>.GROUP:SERVICE.REVENUE_SHARE_PERCENT.CURRENT
EMP:<REF>.GROUP:ADDITIONAL_REVENUE.NET_REVENUE.DELTA
EMP:<REF>.CATEGORY:CHARGER_CABLE.NET_QUANTITY.CURRENT
```

Отдельно сохраняются included-item и missing-cost counters для локального quality gate.

### 6.4 Employee attach-rate

Источник: `EmployeeAttachRateRepository` (`attach-rate-v3`) и rating service (`employee-rating-v1`).

```text
EMP:<REF>.ATTACH:<METRIC_CODE>.NUMERATOR_QUANTITY.CURRENT
EMP:<REF>.ATTACH:<METRIC_CODE>.DENOMINATOR_QUANTITY.CURRENT
EMP:<REF>.ATTACH:<METRIC_CODE>.RATE_PER_HUNDRED.CURRENT
EMP:<REF>.ATTACH:<METRIC_CODE>.RATE_PER_HUNDRED.DELTA
EMP:<REF>.ATTACH:<METRIC_CODE>.STORE_BENCHMARK
```

IncludedInScore не заменяет sample sufficiency; snapshot policy применяет утверждённые пороги.

### 6.5 Рейтинг

Источник: finalized snapshot либо live `EmployeeRatingService`; formula `employee-rating-v1`.

```text
EMP:<REF>.RATING.OVERALL_SCORE.CURRENT
EMP:<REF>.RATING.OVERALL_SCORE.PREVIOUS
EMP:<REF>.RATING.RANK.CURRENT
EMP:<REF>.RATING.RANK.PREVIOUS
EMP:<REF>.RATING.COVERAGE_PERCENT.CURRENT
EMP:<REF>.RATING.CONTRIBUTION_SCORE.CURRENT
EMP:<REF>.RATING.EFFICIENCY_SCORE.CURRENT
EMP:<REF>.RATING.STRUCTURE_SCORE.CURRENT
EMP:<REF>.RATING.ATTACH_SCORE.CURRENT
```

LLM не создаёт второй рейтинг и не назначает rank.

## 7. Team facts и deterministic signals

Team facts формирует snapshot builder только из сотрудников, прошедших metric-specific sufficiency:

```text
TEAM.RATING.ELIGIBLE_COUNT
TEAM.RATING.DISTRIBUTION
TEAM.GROUP:<GROUP>.REVENUE_SHARE_PERCENT.MEDIAN
TEAM.GROUP:<GROUP>.DISTRIBUTION
TEAM.ATTACH:<METRIC_CODE>.RATE_PER_HUNDRED.MEDIAN
TEAM.ATTACH:<METRIC_CODE>.DISTRIBUTION
TEAM.COMPETENCY:<COMPETENCY>.LEADERS
```

Leader/co-leader определяется backend по зафиксированной policy. Преимущество меньше пяти процентов
не позволяет объявить единственного лидера. Candidate signals могут указать на материальный факт,
но не ограничивают LLM только заранее выбранными выводами.

## 8. Запрещённые данные

В snapshot/provider input не входят:

- display name, ФИО, username, телефон и Telegram identifiers;
- зарплата, ставки, премии и payroll statement;
- raw LiveSklad/CRM payload и внешние document IDs;
- тексты переписок;
- Yandex/Telegram credentials, HTTP headers и internal error details;
- произвольный текст из справочников как инструкция модели.

## 9. Нерешённые параметры перед CONFIRMED

До фиксации каталога необходимо подтвердить тестами:

1. Точные materiality thresholds для category/team candidate signals.
2. Правило дневного `TREND` и минимальное число покрытых дней.
3. Metric-specific sufficiency после анализа реального распределения данных.
4. Поведение при отрицательной net revenue/quantity после возвратов.
5. Точный перечень фактов, помещающийся в token preflight с запасом не менее двадцати процентов.

Все пункты являются versioned policy, а не скрытыми константами prompt.
