# Employee Category KPI API

Статус: контракт v1 реализован и проверен 2026-08-01 как обязательная backend-проекция недельного
анализа.

## Endpoint

```http
GET /api/stores/{storeId}/kpi/employees/categories?periodStart=YYYY-MM-DD&periodEnd=YYYY-MM-DD
```

Период включительный, не более 366 дней. Endpoint store-scoped, read-only, читает только
нормализованные PostgreSQL facts и не обращается к LiveSklad.

## Назначение

Общий employee KPI и employee rating содержат только заранее выбранные accessory/service/additional
агрегаты. Они не позволяют воспроизводимо анализировать конкретные категории сотрудника. Эта
проекция возвращает полный set-based category/group slice и является источником:

- карточки категорий сотрудника;
- current/previous facts weekly analytics snapshot;
- employee category strengths/attention areas;
- локального cost/classification quality gate;
- reconciliation с store/category и employee KPI.

## Состав сотрудников

Правила совпадают с `employee-kpi-v1`:

- все назначенные магазину сотрудники, включая нулевые строки;
- исторические сотрудники с фактами периода без текущего назначения;
- одна строка `Не назначен` при документах без employee;
- текущие employee/assignment/ranking flags не переписывают исторические финансовые факты.

## Категории и группы

Каждый сотрудник получает все analytics categories кроме `EXCLUDE`, включая неактивные и нулевые.
`UNMAPPED` остаётся отдельной категорией. Историческая категория берётся из
`sales_document_items.analytics_category_id`.

Группы пересекаются и не суммируются:

1. `PHONES` — `countsAsPhone`;
2. `DEVICES` — `countsAsDevice`;
3. `ACCESSORY` — `categoryKind=ACCESSORY`;
4. `SERVICE` — `SERVICE/WARRANTY/PROTECTION`;
5. `ADDITIONAL_REVENUE` — `countsAsAdditionalRevenue`.

## Формулы

`formulaVersion=employee-category-kpi-v1`,
`categoryFormulaVersion=category-kpi-v1`.

Для category/group:

```text
netRevenue = sales net amount - return net amount
netQuantity = sold quantity - returned quantity
costAmount = sales cost - return cost
grossProfit = netRevenue - costAmount
marginPercent = grossProfit / netRevenue * 100
revenueSharePercent = category-or-group netRevenue / employee netRevenue * 100
```

Возврат относится к employee исходной продажи по нормализованному `sales_documents.employee_id`.
При нулевой employee net revenue доля отсутствует. При любой missing cost строке конкретной
категории/group её cost/gross-profit/margin отсутствуют; revenue/quantity/share остаются доступны.
`ZERO_UNEXPECTED` является warning, но не делает cost incomplete.

## Response

```ts
interface EmployeeCategoryKpiResult {
  storeId: string;
  periodStart: string;
  periodEnd: string;
  formulaVersion: "employee-category-kpi-v1";
  categoryFormulaVersion: "category-kpi-v1";
  employees: EmployeeCategoryKpiEmployee[];
}

interface EmployeeCategoryKpiEmployee {
  employeeId: string | null;
  displayName: string;
  employeeActive: boolean;
  assignedToStore: boolean;
  assignmentActive: boolean;
  participatesInRanking: boolean;
  rankingEligible: boolean;
  unassigned: boolean;
  netRevenue: number;
  dataQuality: EmployeeKpiDataQuality;
  groups: EmployeeCategoryKpiGroup[];
  categories: EmployeeCategoryKpiEntry[];
}

interface EmployeeCategoryKpiMetrics {
  netRevenue: number;
  netQuantity: number;
  costAmount: number | null;
  grossProfit: number | null;
  marginPercent: number | null;
  revenueSharePercent: number | null;
  dataQuality: CategoryKpiDataQuality;
}
```

Category entry также возвращает `categoryCode/name/kind/deviceFamily/active` и три business flags.
Group entry возвращает `groupCode/name` и metrics.

## Reconciliation invariants

Для каждого employee:

- сумма category netRevenue/netQuantity равна employee KPI;
- сумма category included/missing/zero-cost counters равна employee KPI data quality;
- category cost/gross-profit/margin согласуются при complete cost;
- PHONES/DEVICES/ADDITIONAL совпадают с теми же flag rules store category KPI;
- ACCESSORY/SERVICE совпадают с employee rating business-kind rules;
- group overlap не используется для reconciliation totals.

## Ошибки

- `400 INVALID_ARGUMENT` — неверный или обратный период;
- `401 AUTHENTICATION_REQUIRED`;
- `403 ACCESS_DENIED`;
- `404 STORE_NOT_FOUND`.

## Проверки

- unit test общей category/group арифметики и локальной cost-quality;
- controller security/serialization test;
- PostgreSQL integration test для assigned, historical, unassigned и zero scopes;
- reconciliation суммы employee projection с `employee-kpi-v1`;
- generated OpenAPI drift/compatibility check.
