# Weekly review v2: normative API model

Дата: 2026-08-26
Статус: **APPROVED — COMPLETED 2026-08-26**
Родительский контракт: [AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md](AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md)

Этот документ является нормативным дополнением этапа 2. Он фиксирует shape нового direct API.
Названия Java/TypeScript-классов на этапе 3 могут отличаться, но JSON-поля, null semantics,
cardinality и инварианты меняются только через новую версию контракта.

## 1. Serialization rules

- dates — ISO `YYYY-MM-DD`;
- instants — ISO-8601 UTC;
- decimal values — JSON number с scale из product contract;
- UUID и DB IDs не передаются как evidence refs;
- обязательный object никогда не равен `null`;
- обязательный list передаётся как `[]`, а не `null` и не отсутствует;
- optional scalar передаётся как `null`, если его отсутствие имеет пользовательский смысл;
- поле не опускается только из-за provider failure;
- неизвестное enum value является contract error, а не empty state;
- все пользовательские строки приходят локализованными с backend; frontend не интерпретирует code.

## 2. Root resource

```text
WeeklyReviewResponse {
  contractVersion: 2
  versions: VersionSet
  period: PeriodContext
  provenance: Provenance
  reportState: ReportState
  qualitySummary: QualitySummary
  sourceCoverage: SourceCoverage[]
  summary: SummaryBlock
  results: MetricComparison[4]
  revenueDecomposition: RevenueDecomposition
  factors: Factor[0..3]
  salesStructure: SalesStructureBlock
  team: TeamBlock
  employees: EmployeeCard[0..100]
  actions: Action[0..3]
  limitations: Limitation[]
  evidence: Evidence[]
  aiEnhancement: AiEnhancement
}
```

Инварианты root:

- `results` содержит ровно `NET_REVENUE`, `GROSS_PROFIT`, `MARGIN_PERCENT`, `AVERAGE_SALE` в этом
  порядке;
- `reportState=BLOCKED` не удаляет period/provenance/coverage/limitations;
- `aiEnhancement.state` не влияет на `reportState`;
- каждый `evidenceRef` во всех объектах разрешается ровно в один элемент `evidence`;
- каждый `factorId` и `actionId` уникален внутри revision;
- employee ID является стабильным opaque public ID, но не provider ID;
- content полностью относится к одной snapshot revision.

## 3. Version, period and provenance

```text
VersionSet {
  metricsPolicy: string
  snapshotPolicy: string
  qualityPolicy: string
}

PeriodContext {
  timezone: string
  current: DateRange
  previous: DateRange
  currentLabel: string
  previousLabel: string
}

DateRange {
  start: LocalDate
  end: LocalDate
}

Provenance {
  snapshotPublicId: string
  revision: integer >= 1
  calculatedAt: Instant
  sourceDataUpdatedAt: Instant | null
  revisionChanged: boolean
  previousRevisionPublishedAt: Instant | null
}
```

Инварианты периода:

- обе даты inclusive;
- current и previous содержат ровно семь дней;
- `previous.end = current.start - 1 day`;
- `previous.start = previous.end - 6 days`;
- `current.end` — воскресенье в timezone магазина;
- label соответствует переданным датам, но не является источником расчёта.

## 4. Coverage and quality

```text
SourceCoverage {
  sourceCode: SALES | RETURNS | CLASSIFICATION | COST | EMPLOYEE_ATTRIBUTION | SHIFTS
  requiredForReport: boolean
  affectedBlockIds: string[]
  currentThroughDate: LocalDate | null
  previousThroughDate: LocalDate | null
  state: COMPLETE | PARTIAL | MISSING | NOT_REQUIRED
  message: string | null
}

QualitySummary {
  blockingCount: integer >= 0
  warningCount: integer >= 0
  affectedBlockCount: integer >= 0
  message: string
}
```

- `SALES` и `RETURNS` обязательны для `results:NET_REVENUE`.
- `ORDERS` намеренно отсутствует, пока на странице нет order metrics.
- latest sync job state не включается в `SourceCoverage`; учитывается фактическое покрытие.
- `message=null` при `COMPLETE/NOT_REQUIRED`.

## 5. Summary

```text
SummaryBlock {
  blockId: "summary"
  state: BlockState
  outcome: NarrativeItem | null
  positive: NarrativeItem | null
  risk: NarrativeItem | null
  generatedBy: DETERMINISTIC | AI_ENHANCED
}

NarrativeItem {
  itemId: string
  text: string
  effect: POSITIVE | NEGATIVE | NEUTRAL | UNKNOWN
  evidenceRefs: string[1..5]
}
```

- `outcome` обязателен при `reportState=READY/PARTIAL`.
- `positive/risk` отсутствуют, если нет material candidate соответствующего effect.
- `generatedBy=AI_ENHANCED` только после полной semantic validation schema4.
- fallback меняет только текст, но не item IDs/evidence/effect.

## 6. Metric comparison

```text
MetricComparison {
  metricId: string
  code: MetricCode
  label: string
  unit: RUB | PERCENT | PER_100 | COUNT | HOURS
  current: decimal | null
  previous: decimal | null
  absoluteDelta: decimal | null
  changePercent: decimal | null
  comparisonKind: PERCENT_AVAILABLE | NO_BASE | NON_POSITIVE_BASE | UNAVAILABLE
  direction: UP | DOWN | FLAT | UNKNOWN
  effect: POSITIVE | NEGATIVE | NEUTRAL | UNKNOWN
  metricState: MetricState
  sufficiency: SUFFICIENT | LIMITED | INSUFFICIENT | NOT_EVALUATED
  materiality: MATERIAL | NOT_MATERIAL | NOT_EVALUATED
  currentSample: Sample | null
  previousSample: Sample | null
  evidenceRefs: string[1..10]
}

Sample {
  numerator: decimal | null
  denominator: decimal | null
  numeratorLabel: string | null
  denominatorLabel: string | null
}
```

- `absoluteDelta` не может существовать, если current или previous равен `null`.
- `materiality=MATERIAL` требует `sufficiency=SUFFICIENT`.
- `metricState=UNAVAILABLE` требует current либо previous `null` и limitation.
- `FLAT` означает exact delta `0`, а не «ниже порога».
- `NOT_MATERIAL` может иметь direction `UP/DOWN`.

## 7. Revenue decomposition

```text
RevenueDecomposition {
  salesRevenue: MetricComparison
  returnRevenue: MetricComparison
  netRevenue: MetricComparison
  saleDocumentCount: MetricComparison
  returnDocumentCount: MetricComparison
  identityValid: boolean
}
```

Для current и previous отдельно выполняется:

```text
salesRevenue - returnRevenue = netRevenue
```

`returnRevenue` передаётся как неотрицательная абсолютная сумма. Его effect при росте — negative.
`identityValid=false` является blocking contract violation и не публикуется.

## 8. Factors

```text
Factor {
  factorId: string
  kind: RETURN_CHANGE | STRUCTURE_CHANGE | ATTACH_CHANGE
  title: string
  detail: string
  comparison: MetricComparison
  contributionAmount: decimal | null
  effect: POSITIVE | NEGATIVE
  evidenceRefs: string[1..10]
}
```

- factor всегда material и sufficient;
- `contributionAmount` разрешён только для additive revenue decomposition;
- при `contributionAmount=null` title/detail не содержат причинный союз;
- metric не дублируется в двух factors.

## 9. Sales structure

```text
SalesStructureBlock {
  blockId: "sales-structure"
  state: BlockState
  root: StructureNode
  attachMetrics: AttachMetric[]
  limitations: string[]
}

StructureNode {
  nodeId: string
  code: NET_REVENUE | DEVICES | PHONES | OTHER_DEVICES | ADDITIONAL_REVENUE |
        ACCESSORY | SERVICE | OTHER_ADDITIONAL | OTHER
  label: string
  subtotal: boolean
  childrenIncludedInValue: boolean
  comparison: MetricComparison
  shareComparison: MetricComparison
  children: StructureNode[]
}

AttachMetric {
  metricId: string
  code: string
  label: string
  comparison: MetricComparison
}
```

- root всегда `NET_REVENUE`, `subtotal=true`, `childrenIncludedInValue=true`;
- все дочерние nodes приходят в стабильном порядке contract;
- frontend не вычисляет residual и не агрегирует nodes;
- отсутствие configured attach metrics даёт `[]` и block message, но не ограничивает structure.

## 10. Team

```text
TeamBlock {
  blockId: "team"
  state: BlockState
  roster: RosterSummary
  observations: Observation[0..2]
  attentionEmployeeCount: integer >= 0
  benchmarkPolicy: BenchmarkPolicy
  limitations: string[]
}

RosterSummary {
  activeAssignedWithActivity: integer >= 0
  participatesInBenchmark: integer >= 0
  sufficientByAnyMetric: integer >= 0
  limitedOrInsufficient: integer >= 0
  excludedFromBenchmark: integer >= 0
}

BenchmarkPolicy {
  method: MEDIAN
  minimumEligibleCount: 3
  label: string
}

Observation {
  observationId: string
  title: string
  detail: string
  effect: POSITIVE | NEGATIVE | NEUTRAL
  evidenceRefs: string[1..10]
}
```

`attentionEmployeeCount` равно числу карточек `sortGroup=ATTENTION`. Team payload не содержит
employee IDs, ФИО, персональные значения, персональные observations или actions. UI использует count
только для ссылки-фильтра на root `employees`; персональный контент не копируется в team block.

Team `observations` разрешены только для агрегата или однонаправленного material изменения минимум
у двух сотрудников. Observation, описывающий одного сотрудника, является contract violation.

## 11. Employees

```text
EmployeeCard {
  employeePublicId: string
  displayName: string
  participatesInBenchmark: boolean
  sortGroup: ATTENTION | LIMITED | POSITIVE | STABLE
  metrics: EmployeeMetricSet
  ownDynamics: Observation[0..2]
  peerComparison: PeerComparison | null
  strength: Observation | null
  attention: Observation | null
  action: Action | null
  limitations: string[]
}

EmployeeMetricSet {
  completedSales: MetricComparison
  netRevenue: MetricComparison
  additionalRevenue: MetricComparison
  additionalShare: MetricComparison
  shiftCount: MetricComparison
  workedHours: MetricComparison
  revenuePerHour: MetricComparison
  attachMetrics: AttachMetric[0..2]
}

PeerComparison {
  metricCode: string
  employeeValue: decimal
  benchmarkValue: decimal
  benchmarkMethod: MEDIAN
  eligibleCount: integer >= 3
  absoluteDelta: decimal
  changePercent: decimal | null
  effect: POSITIVE | NEGATIVE | NEUTRAL
  evidenceRefs: string[2..10]
}
```

- employee metrics всегда содержат current и previous внутри `MetricComparison`;
- отсутствие shifts не делает `netRevenue` unavailable;
- `peerComparison=null`, если employee excluded или eligible count меньше 3;
- повторение одного observation в strength/attention запрещено;
- одинаковый generic text у разных employees является acceptance failure.

## 12. Actions

```text
Action {
  actionId: string
  priority: HIGH | MEDIUM | LOW
  actionType: RESTORE_METRIC | REVIEW_RETURN_PATTERN | COACH_EMPLOYEE | MAINTAIN_PRACTICE
  scope: STORE | TEAM | EMPLOYEE
  employeePublicId: string | null
  title: string
  metricCode: string
  target: ActionTarget
  check: string
  horizon: NEXT_FULL_WEEK
  generatedBy: DETERMINISTIC | AI_ENHANCED
  evidenceRefs: string[1..10]
}

ActionTarget {
  operator: AT_LEAST | AT_MOST
  value: decimal
  unit: RUB | PERCENT | PER_100 | COUNT
}
```

- target принадлежит backend и не меняется AI enrichment;
- store actions также присутствуют в root `actions`; employee action присутствует только в card;
- один action ID не встречается в двух местах;
- `COACH_EMPLOYEE` не содержит кадровой санкции и использует только own sufficient evidence.

## 13. Limitations

```text
Limitation {
  limitationId: string
  code: string
  severity: WARNING | BLOCKING
  scope: STORE | BLOCK | TEAM | EMPLOYEE | METRIC
  employeePublicId: string | null
  affectedBlockIds: string[1..]
  affectedMetricCodes: string[]
  period: DateRange
  affectedCount: integer >= 1
  summary: string
  resolution: string | null
  evidenceRefs: string[]
}
```

- code маршрутизируется typed mapping, а не global count;
- одна source issue может создать несколько metric limitations только с разными affected scopes;
- одинаковые scope/code/period схлопываются с `affectedCount`;
- employee card хранит короткие локализованные ограничения блока; канонические period-scoped
  limitations находятся в root.

## 14. Evidence

```text
Evidence {
  evidenceRef: string
  scope: STORE | TEAM | EMPLOYEE
  employeePublicId: string | null
  metricCode: string
  label: string
  unit: RUB | PERCENT | PER_100 | COUNT | HOURS | STATUS
  currentPeriod: DateRange
  previousPeriod: DateRange | null
  currentValue: decimal | string | null
  previousValue: decimal | string | null
  currentNumerator: decimal | null
  currentDenominator: decimal | null
  previousNumerator: decimal | null
  previousDenominator: decimal | null
  formulaVersion: string
  sufficiency: SUFFICIENT | LIMITED | INSUFFICIENT | NOT_EVALUATED
  materiality: MATERIAL | NOT_MATERIAL | NOT_EVALUATED
  available: boolean
}
```

- public evidence содержит display label и безопасный public employee ID;
- raw document IDs, payloads и provider prompts отсутствуют;
- `available=false` требует limitation и объясняет, почему value равен `null`.

## 15. AI enhancement

```text
AiEnhancement {
  state: PREPARING | READY | DELAYED | UNAVAILABLE | DISABLED | NOT_APPLICABLE
  promptVersion: string | null
  contentSchemaVersion: integer | null
  publishedAt: Instant | null
}
```

Frontend не ветвит доступность report content по этому объекту. Поля нужны для provenance и
операционной диагностики. Пользователю не показывается warning при `UNAVAILABLE`, если
deterministic report готов.

## 16. Block state and presentation matrix

| Блок | Empty state | Partial/insufficient | Desktop | Mobile |
| --- | --- | --- | --- | --- |
| Period | невозможен | точная coverage подпись | одна строка + badge | две короткие строки |
| Summary | null только при BLOCKED | deterministic text | до 3 строк | тот же порядок |
| Results | 4 cards всегда | значение `—` + причина | grid 4 | horizontal/2-column cards |
| Factors | «Существенных изменений нет» | affected factor не создаётся | до 3 rows | stacked cards |
| Structure | нулевая структура | affected nodes limited | tree + attach table | nested list + metric cards |
| Team | «Нет сотрудников с активностью» | counts + exact reasons | агрегаты + attention count | stacked aggregates |
| Employees | список пуст | per-metric limitation | первые 5 + expand | collapsed cards |
| Actions | «Действия … не требуются» | limited evidence не создаёт action | numbered list | cards |
| Limitations | блок скрыт | canonical unique list | bottom + inline links | accordion + inline links |

Общие accessibility-инварианты:

- каждый interactive control доступен клавиатурой;
- button имеет visible focus и accessible name;
- disclosure сообщает `aria-expanded`;
- таблицы имеют headers, mobile cards — эквивалентные labels;
- positive/negative state передаётся текстом/иконкой, не только цветом;
- суммы не читаются screen reader как набор отдельных символов.

## 17. Contract rejection conditions

Snapshot/report не публикуется как v2, если:

- нарушены границы недель;
- revenue identity не сходится;
- root evidence ref не разрешается;
- material factor использует limited/insufficient metric;
- action не имеет backend target/evidence;
- peer comparison имеет eligible count меньше 3;
- team block содержит employee ID, ФИО, персональный показатель, вывод или action;
- plan fact, plan horizon или plan-derived composite score попал в payload;
- provider изменил число, период, factor/action set либо добавил employee content;
- required list/object отсутствует или заменён `null`;
- один блок относится к другой snapshot revision.

Эти условия должны быть одновременно закреплены backend contract tests, OpenAPI tests,
frontend parser tests и offline semantic validator.
