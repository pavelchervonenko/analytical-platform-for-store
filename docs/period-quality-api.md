# Period Quality API

Статус: реализовано и сверено 2026-07-23. Это основной banner готовности выбранного месяца;
маршрутизация кнопок по `recommendedAction` находится в `docs/frontend-actions.md`.

## Назначение

Периодный контроль качества отвечает на один управленческий вопрос: достаточно ли данных выбранного
магазина и месяца, чтобы доверять выполнению плана, рейтингу сотрудников и зарплате. Он не
пересчитывает бизнес-формулы, а объединяет уже существующие границы KPI, plan progress, rating,
payroll readiness и payroll freshness.

Endpoint:

```http
GET /api/stores/{storeId}/period-quality/{YYYY-MM}?asOf=YYYY-MM-DD
```

`asOf` обязателен и должен находиться внутри месяца. Для текущего месяца передается дата среза, для
закрытого месяца обычно последнее число. Доступ защищен назначением магазина: чужой магазин для
`MANAGER` возвращает `403`, `ADMIN` может читать любой магазин.

## Итоговый статус

Ответ содержит четыре независимые области: `SOURCE_DATA`, `STORE_PLAN`, `EMPLOYEE_RATING` и
`PAYROLL`. Статус области и общий `status` имеют значения `OK`, `WARNING`, `ERROR`; приоритет всегда
`ERROR -> WARNING -> OK`. Информационная запись сама по себе не ухудшает `OK`.

`readyForDecisions=false`, если найдена хотя бы одна ошибка. Это готовый UI/LLM-флаг, но он не
заменяет специализированные backend-проверки: payroll повторно проверяет readiness/freshness при
изменении статуса, а rating finalization — закрытие периода.

## Структура ответа

```ts
type PeriodQualityAreaCode =
  | "SOURCE_DATA"
  | "STORE_PLAN"
  | "EMPLOYEE_RATING"
  | "PAYROLL";

type PeriodQualityAction =
  | "NONE"
  | "WAIT_FOR_SYNC"
  | "RUN_SYNC"
  | "SET_STORE_PLAN"
  | "UPDATE_WORK_SCHEDULE"
  | "REVIEW_EMPLOYEE_ELIGIBILITY"
  | "CLASSIFY_PRODUCTS"
  | "PROVIDE_COST_DATA"
  | "CALCULATE_PAYROLL"
  | "RECALCULATE_PAYROLL"
  | "FINALIZE_RATING"
  | "REVIEW_DATA_ISSUES";

interface PeriodQualityIssueView {
  key: string;
  area: PeriodQualityAreaCode;
  code: string;
  severity: "INFO" | "WARNING" | "ERROR";
  message: string;
  affectedCount: number | null;
  recommendedAction: PeriodQualityAction;
}

interface PeriodQualityAreaView {
  code: PeriodQualityAreaCode;
  status: "OK" | "WARNING" | "ERROR";
  ready: boolean;
  issueCount: number;
  errorCount: number;
  warningCount: number;
  infoCount: number;
}
```

Корневой `StorePeriodQualityView` дополнительно содержит:

- `periodMonth`, `periodStart`, `periodEnd`, `asOfDate` как ISO-даты;
- `sourceData`: покрытие до даты среза, классификация, себестоимость и открытые расхождения;
- `storePlan`: наличие плана и качество входов plan progress;
- `employeeRating`: число сотрудников, eligible/со сменами/в рейтинге, продажи без смен,
  недостаточное покрытие score и lifecycle истории;
- `payroll`: readiness, счетчики блокирующих проблем, наличие расчета, его статус и freshness;
- единый отсортированный `issues[]` и `checkedAt`.

Проблемы сортируются по `ERROR -> WARNING -> INFO`, затем по области и коду. `key` используется как
стабильный ключ строки. Сообщения безопасны: raw payload, внешние ID документов, внутренние хеши и
metadata не возвращаются.

## Основные причины

### Исходные данные

- `SOURCE_DATA_NOT_SYNCED`, `SOURCE_SYNC_FAILED`;
- `SOURCE_DATA_STALE`, `SOURCE_SYNC_IN_PROGRESS`;
- `SOURCE_DATA_INCOMPLETE_THROUGH_AS_OF`;
- `SOURCE_PRODUCTS_UNMAPPED`;
- `SOURCE_COST_DATA_MISSING`, `SOURCE_COST_DATA_ZERO_UNEXPECTED`;
- `SOURCE_OPEN_QUALITY_ISSUES`.

### План и рейтинг

- `STORE_PLAN_MISSING`;
- `RATING_PLAN_COVERAGE_INCOMPLETE`, `RATING_INPUT_DATA_INCOMPLETE`;
- `RATING_NO_ELIGIBLE_EMPLOYEES`, `RATING_NO_EMPLOYEES_WITH_SHIFTS`;
- `RATING_SALES_WITHOUT_SHIFT`, `RATING_NO_RANKED_EMPLOYEES`;
- `RATING_SCORE_COVERAGE_INSUFFICIENT`, `RATING_HISTORY_NOT_FINALIZED`.

### Зарплата

- `PAYROLL_PLAN_MISSING`, `PAYROLL_SCHEME_MISSING`;
- `PAYROLL_PRODUCTS_UNMAPPED`, `PAYROLL_REQUIRED_COST_MISSING`;
- `PAYROLL_DAYS_WITHOUT_SHIFT`, `PAYROLL_PERIOD_DATA_INCOMPLETE`;
- `PAYROLL_NOT_CALCULATED` — информационный lifecycle-сигнал;
- `PAYROLL_RECALCULATION_REQUIRED` — последняя ревизия `STALE` или legacy `UNKNOWN`.

## Использование frontend

- Загружать после выбора магазина, месяца и `asOf`; все три значения входят в query key.
- Общий banner строить по `status` и `readyForDecisions`, вкладки — по `areas`.
- Не вычислять статус повторно из счетчиков и не угадывать действие по тексту: использовать
  `code` и `recommendedAction`.
- Для детального исправления переходить в существующие экраны sync/data quality, плана, смен,
  классификации, рейтинга или payroll readiness.
