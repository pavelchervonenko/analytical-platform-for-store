import { z } from "zod";

const effectSchema = z.enum(["POSITIVE", "NEGATIVE", "NEUTRAL", "UNKNOWN"]);
const blockStateSchema = z.enum(["READY", "LIMITED", "INSUFFICIENT", "NOT_APPLICABLE"]);
const metricStateSchema = z.enum(["READY", "LIMITED", "UNAVAILABLE", "NOT_APPLICABLE"]);
const sufficiencySchema = z.enum(["SUFFICIENT", "LIMITED", "INSUFFICIENT", "NOT_EVALUATED"]);
const materialitySchema = z.enum(["MATERIAL", "NOT_MATERIAL", "NOT_EVALUATED"]);
const unitSchema = z.enum(["RUB", "PERCENT", "PER_100", "COUNT", "HOURS", "STATUS"]);
const generatedBySchema = z.enum(["DETERMINISTIC", "AI_ENHANCED"]);
const evidenceRefsSchema = z.array(z.string().min(1)).min(1);
const dateRangeSchema = z.object({
  start: z.iso.date(),
  end: z.iso.date()
});

const sampleSchema = z.object({
  numerator: z.number().nullable(),
  denominator: z.number().nullable(),
  numeratorLabel: z.string().nullable(),
  denominatorLabel: z.string().nullable()
});

export const weeklyReviewMetricSchema = z.object({
  metricId: z.string().min(1),
  code: z.string().min(1),
  label: z.string().min(1),
  unit: unitSchema,
  current: z.number().nullable(),
  previous: z.number().nullable(),
  absoluteDelta: z.number().nullable(),
  changePercent: z.number().nullable(),
  comparisonKind: z.enum([
    "PERCENT_AVAILABLE",
    "NO_BASE",
    "NON_POSITIVE_BASE",
    "UNAVAILABLE"
  ]),
  direction: z.enum(["UP", "DOWN", "FLAT", "UNKNOWN"]),
  effect: effectSchema,
  metricState: metricStateSchema,
  sufficiency: sufficiencySchema,
  materiality: materialitySchema,
  currentSample: sampleSchema.nullable(),
  previousSample: sampleSchema.nullable(),
  evidenceRefs: evidenceRefsSchema
});

const narrativeSchema = z.object({
  itemId: z.string().min(1),
  text: z.string().min(1),
  effect: effectSchema,
  evidenceRefs: evidenceRefsSchema
});

const observationSchema = z.object({
  observationId: z.string().min(1),
  title: z.string().min(1),
  detail: z.string().min(1),
  effect: effectSchema,
  evidenceRefs: evidenceRefsSchema
});

const attachMetricSchema = z.object({
  metricId: z.string().min(1),
  code: z.string().min(1),
  label: z.string().min(1),
  comparison: weeklyReviewMetricSchema
});

const actionSchema = z.object({
  actionId: z.string().min(1),
  priority: z.enum(["HIGH", "MEDIUM", "LOW"]),
  actionType: z.enum([
    "RESTORE_METRIC",
    "REVIEW_RETURN_PATTERN",
    "COACH_EMPLOYEE",
    "MAINTAIN_PRACTICE"
  ]),
  scope: z.enum(["STORE", "TEAM", "EMPLOYEE"]),
  employeePublicId: z.string().nullable(),
  title: z.string().min(1),
  metricCode: z.string().min(1),
  target: z.object({
    operator: z.enum(["AT_LEAST", "AT_MOST"]),
    value: z.number(),
    unit: z.enum(["RUB", "PERCENT", "PER_100", "COUNT"])
  }),
  check: z.string().min(1),
  horizon: z.literal("NEXT_FULL_WEEK"),
  generatedBy: generatedBySchema,
  evidenceRefs: evidenceRefsSchema
}).superRefine((action, context) => {
  const employeeScoped = action.scope === "EMPLOYEE";
  if (employeeScoped !== (action.employeePublicId !== null)) {
    context.addIssue({
      code: "custom",
      message: "Employee action scope must match employeePublicId",
      path: ["employeePublicId"]
    });
  }
});

export interface WeeklyReviewStructureNode {
  nodeId: string;
  code: string;
  label: string;
  subtotal: boolean;
  childrenIncludedInValue: boolean;
  comparison: z.infer<typeof weeklyReviewMetricSchema>;
  shareComparison: z.infer<typeof weeklyReviewMetricSchema>;
  children: WeeklyReviewStructureNode[];
}

const structureNodeSchema: z.ZodType<WeeklyReviewStructureNode> = z.lazy(() => z.object({
  nodeId: z.string().min(1),
  code: z.string().min(1),
  label: z.string().min(1),
  subtotal: z.boolean(),
  childrenIncludedInValue: z.boolean(),
  comparison: weeklyReviewMetricSchema,
  shareComparison: weeklyReviewMetricSchema,
  children: z.array(structureNodeSchema)
}));

const employeeMetricSetSchema = z.object({
  completedSales: weeklyReviewMetricSchema,
  netRevenue: weeklyReviewMetricSchema,
  additionalRevenue: weeklyReviewMetricSchema,
  additionalShare: weeklyReviewMetricSchema,
  shiftCount: weeklyReviewMetricSchema,
  workedHours: weeklyReviewMetricSchema,
  revenuePerHour: weeklyReviewMetricSchema,
  attachMetrics: z.array(attachMetricSchema).max(2)
});

const evidenceValueSchema = z.union([
  z.string(),
  z.number(),
  z.boolean(),
  z.null()
]);

const limitationSchema = z.object({
  limitationId: z.string().min(1),
  code: z.string().min(1),
  severity: z.enum(["WARNING", "BLOCKING"]),
  scope: z.enum(["STORE", "BLOCK", "TEAM", "EMPLOYEE", "METRIC"]),
  employeePublicId: z.string().nullable(),
  affectedBlockIds: z.array(z.string().min(1)).min(1),
  affectedMetricCodes: z.array(z.string()),
  period: dateRangeSchema,
  affectedCount: z.number().int().positive(),
  summary: z.string().min(1),
  resolution: z.string().nullable(),
  evidenceRefs: z.array(z.string())
});

const evidenceSchema = z.object({
  evidenceRef: z.string().min(1),
  scope: z.enum(["STORE", "TEAM", "EMPLOYEE"]),
  employeePublicId: z.string().nullable(),
  metricCode: z.string().min(1),
  label: z.string().min(1),
  unit: unitSchema,
  currentPeriod: dateRangeSchema,
  previousPeriod: dateRangeSchema.nullable(),
  currentValue: evidenceValueSchema,
  previousValue: evidenceValueSchema,
  currentNumerator: z.number().nullable(),
  currentDenominator: z.number().nullable(),
  previousNumerator: z.number().nullable(),
  previousDenominator: z.number().nullable(),
  formulaVersion: z.string().min(1),
  sufficiency: sufficiencySchema,
  materiality: materialitySchema,
  available: z.boolean()
});

export const weeklyReviewSchema = z.object({
  contractVersion: z.literal(2),
  versions: z.object({
    metricsPolicy: z.string().min(1),
    snapshotPolicy: z.string().min(1),
    qualityPolicy: z.string().min(1)
  }),
  period: z.object({
    timezone: z.string().min(1),
    current: dateRangeSchema,
    previous: dateRangeSchema,
    currentLabel: z.string().min(1),
    previousLabel: z.string().min(1)
  }),
  provenance: z.object({
    snapshotPublicId: z.string().min(1),
    revision: z.number().int().positive(),
    calculatedAt: z.iso.datetime({ offset: true }),
    sourceDataUpdatedAt: z.iso.datetime({ offset: true }).nullable(),
    revisionChanged: z.boolean(),
    previousRevisionPublishedAt: z.iso.datetime({ offset: true }).nullable()
  }),
  reportState: z.enum(["PREPARING", "READY", "PARTIAL", "BLOCKED"]),
  qualitySummary: z.object({
    blockingCount: z.number().int().nonnegative(),
    warningCount: z.number().int().nonnegative(),
    affectedBlockCount: z.number().int().nonnegative(),
    message: z.string().min(1)
  }),
  sourceCoverage: z.array(z.object({
    sourceCode: z.enum([
      "SALES",
      "RETURNS",
      "CLASSIFICATION",
      "COST",
      "EMPLOYEE_ATTRIBUTION",
      "SHIFTS"
    ]),
    requiredForReport: z.boolean(),
    affectedBlockIds: z.array(z.string()),
    currentThroughDate: z.iso.date().nullable(),
    previousThroughDate: z.iso.date().nullable(),
    state: z.enum(["COMPLETE", "PARTIAL", "MISSING", "NOT_REQUIRED"]),
    message: z.string().nullable()
  })),
  summary: z.object({
    blockId: z.literal("summary"),
    state: blockStateSchema,
    outcome: narrativeSchema.nullable(),
    positive: narrativeSchema.nullable(),
    risk: narrativeSchema.nullable(),
    generatedBy: generatedBySchema
  }),
  results: z.array(weeklyReviewMetricSchema).length(4),
  revenueDecomposition: z.object({
    salesRevenue: weeklyReviewMetricSchema,
    returnRevenue: weeklyReviewMetricSchema,
    netRevenue: weeklyReviewMetricSchema,
    saleDocumentCount: weeklyReviewMetricSchema,
    returnDocumentCount: weeklyReviewMetricSchema,
    identityValid: z.literal(true)
  }),
  factors: z.array(z.object({
    factorId: z.string().min(1),
    kind: z.enum(["RETURN_CHANGE", "STRUCTURE_CHANGE", "ATTACH_CHANGE"]),
    title: z.string().min(1),
    detail: z.string().min(1),
    comparison: weeklyReviewMetricSchema,
    contributionAmount: z.number().nullable(),
    effect: z.enum(["POSITIVE", "NEGATIVE"]),
    evidenceRefs: evidenceRefsSchema
  })).max(3),
  salesStructure: z.object({
    blockId: z.literal("sales-structure"),
    state: blockStateSchema,
    root: structureNodeSchema,
    attachMetrics: z.array(attachMetricSchema),
    limitations: z.array(z.string())
  }),
  team: z.object({
    blockId: z.literal("team"),
    state: blockStateSchema,
    roster: z.object({
      activeAssignedWithActivity: z.number().int().nonnegative(),
      participatesInBenchmark: z.number().int().nonnegative(),
      sufficientByAnyMetric: z.number().int().nonnegative(),
      limitedOrInsufficient: z.number().int().nonnegative(),
      excludedFromBenchmark: z.number().int().nonnegative()
    }),
    observations: z.array(observationSchema).max(2),
    attentionEmployeeCount: z.number().int().nonnegative(),
    benchmarkPolicy: z.object({
      method: z.literal("MEDIAN"),
      minimumEligibleCount: z.literal(3),
      label: z.string().min(1)
    }),
    limitations: z.array(z.string())
  }),
  employees: z.array(z.object({
    employeePublicId: z.string().min(1),
    displayName: z.string().min(1),
    participatesInBenchmark: z.boolean(),
    sortGroup: z.enum(["ATTENTION", "LIMITED", "POSITIVE", "STABLE"]),
    metrics: employeeMetricSetSchema,
    ownDynamics: z.array(observationSchema).max(2),
    peerComparison: z.object({
      metricCode: z.literal("NET_REVENUE"),
      employeeValue: z.number(),
      benchmarkValue: z.number(),
      benchmarkMethod: z.literal("MEDIAN"),
      eligibleCount: z.number().int().min(3),
      absoluteDelta: z.number(),
      changePercent: z.number().nullable(),
      effect: effectSchema,
      evidenceRefs: evidenceRefsSchema
    }).nullable(),
    strength: observationSchema.nullable(),
    attention: observationSchema.nullable(),
    action: actionSchema.nullable(),
    limitations: z.array(z.string())
  })).max(100),
  actions: z.array(actionSchema).max(3),
  limitations: z.array(limitationSchema),
  evidence: z.array(evidenceSchema),
  aiEnhancement: z.object({
    state: z.enum([
      "PREPARING",
      "READY",
      "DELAYED",
      "UNAVAILABLE",
      "DISABLED",
      "NOT_APPLICABLE"
    ]),
    promptVersion: z.string().nullable(),
    contentSchemaVersion: z.number().int().positive().nullable(),
    publishedAt: z.iso.datetime({ offset: true }).nullable()
  })
}).superRefine((review, context) => {
  const addIssue = (message: string, path: PropertyKey[]) => context.addIssue({
    code: "custom",
    message,
    path
  });
  const dayInMilliseconds = 86_400_000;
  const dateValue = (value: string) => Date.parse(`${value}T00:00:00Z`);
  const periodRanges = [
    ["current", review.period.current],
    ["previous", review.period.previous]
  ] as const;
  periodRanges.forEach(([name, range]) => {
    const start = dateValue(range.start);
    const end = dateValue(range.end);
    if (start > end) {
      addIssue("Weekly period start must not be after its end", ["period", name]);
    }
    if (end - start !== 6 * dayInMilliseconds) {
      addIssue("Weekly period must contain exactly seven days", ["period", name]);
    }
    if (new Date(start).getUTCDay() !== 1 || new Date(end).getUTCDay() !== 0) {
      addIssue("Weekly period must run from Monday through Sunday", ["period", name]);
    }
  });
  if (dateValue(review.period.previous.end) + dayInMilliseconds
      !== dateValue(review.period.current.start)) {
    addIssue("Current and previous weekly periods must be adjacent", ["period"]);
  }
  const expectedCore = [
    ["NET_REVENUE", "RUB"],
    ["GROSS_PROFIT", "RUB"],
    ["MARGIN_PERCENT", "PERCENT"],
    ["AVERAGE_SALE", "RUB"]
  ] as const;
  expectedCore.forEach(([code, unit], index) => {
    const metric = review.results[index];
    if (metric?.code !== code || metric.unit !== unit) {
      addIssue("Core weekly metrics have an unexpected code, unit or order", ["results", index]);
    }
  });

  const decomposition = [
    [review.revenueDecomposition.salesRevenue, "SALES_REVENUE", "RUB"],
    [review.revenueDecomposition.returnRevenue, "RETURN_REVENUE", "RUB"],
    [review.revenueDecomposition.netRevenue, "NET_REVENUE", "RUB"],
    [review.revenueDecomposition.saleDocumentCount, "SALE_DOCUMENT_COUNT", "COUNT"],
    [review.revenueDecomposition.returnDocumentCount, "RETURN_DOCUMENT_COUNT", "COUNT"]
  ] as const;
  decomposition.forEach(([metric, code, unit], index) => {
    if (metric.code !== code || metric.unit !== unit) {
      addIssue("Revenue decomposition has an unexpected metric code or unit", ["revenueDecomposition", index]);
    }
  });
  const assertRevenueIdentity = (
    values: readonly [number | null, number | null, number | null],
    period: "current" | "previous"
  ) => {
    if (values.some((value) => value === null)) return;
    const [sales, returns, net] = values as [number, number, number];
    const toCents = (value: number) => Math.round(value * 100);
    if (toCents(sales) - toCents(returns) !== toCents(net)) {
      addIssue(
        "Revenue decomposition must satisfy sales minus returns equals net revenue",
        ["revenueDecomposition", period]
      );
    }
  };
  assertRevenueIdentity([
    review.revenueDecomposition.salesRevenue.current,
    review.revenueDecomposition.returnRevenue.current,
    review.revenueDecomposition.netRevenue.current
  ], "current");
  assertRevenueIdentity([
    review.revenueDecomposition.salesRevenue.previous,
    review.revenueDecomposition.returnRevenue.previous,
    review.revenueDecomposition.netRevenue.previous
  ], "previous");

  if ((review.reportState === "READY" || review.reportState === "PARTIAL")
      && review.summary.outcome === null) {
    addIssue("READY and PARTIAL reviews require a summary outcome", ["summary", "outcome"]);
  }
  const hasLocalBlockConstraint = [
    review.summary.state,
    review.salesStructure.state,
    review.team.state
  ].some((state) => state !== "READY");
  if (review.reportState === "READY"
      && (review.qualitySummary.blockingCount !== 0
        || review.qualitySummary.warningCount !== 0
        || hasLocalBlockConstraint)) {
    addIssue("READY review requires ready local blocks and no quality issues", ["reportState"]);
  }
  if (review.reportState === "PARTIAL"
      && (review.qualitySummary.blockingCount !== 0
        || (review.qualitySummary.warningCount < 1 && !hasLocalBlockConstraint))) {
    addIssue("PARTIAL review requires a warning or a constrained local block and no blockers", ["reportState"]);
  }
  if (review.reportState === "BLOCKED" && review.qualitySummary.blockingCount < 1) {
    addIssue("BLOCKED review requires at least one blocker", ["qualitySummary", "blockingCount"]);
  }
  if ((review.summary.state === "READY" || review.summary.state === "LIMITED")
      && review.summary.outcome === null) {
    addIssue("Available summary block requires an outcome", ["summary", "outcome"]);
  }
  review.factors.forEach((factor, factorIndex) => {
    if (factor.comparison.metricState !== "READY"
        || factor.comparison.sufficiency !== "SUFFICIENT"
        || factor.comparison.materiality !== "MATERIAL") {
      addIssue(
        "Factors require a ready, sufficient and material comparison",
        ["factors", factorIndex, "comparison"]
      );
    }
    if (factor.effect !== factor.comparison.effect) {
      addIssue(
        "Factor effect must match its comparison effect",
        ["factors", factorIndex, "effect"]
      );
    }
  });

  const employeeMetricContract = [
    ["completedSales", "COMPLETED_SALES", "COUNT"],
    ["netRevenue", "NET_REVENUE", "RUB"],
    ["additionalRevenue", "ADDITIONAL_REVENUE", "RUB"],
    ["additionalShare", "ADDITIONAL_SHARE", "PERCENT"],
    ["shiftCount", "SHIFT_COUNT", "COUNT"],
    ["workedHours", "WORKED_HOURS", "HOURS"],
    ["revenuePerHour", "REVENUE_PER_HOUR", "RUB"]
  ] as const;
  review.employees.forEach((employee, employeeIndex) => {
    employeeMetricContract.forEach(([field, code, unit]) => {
      const metric = employee.metrics[field];
      if (metric.code !== code || metric.unit !== unit) {
        addIssue("Employee metric has an unexpected code or unit", ["employees", employeeIndex, "metrics", field]);
      }
    });
    if (employee.action && (employee.action.scope !== "EMPLOYEE"
        || employee.action.employeePublicId !== employee.employeePublicId)) {
      addIssue(
        "Employee action must target its owning employee",
        ["employees", employeeIndex, "action", "employeePublicId"]
      );
    }
  });
  review.actions.forEach((action, actionIndex) => {
    if (action.scope === "EMPLOYEE" || action.employeePublicId !== null) {
      addIssue(
        "Root actions must target the store or team",
        ["actions", actionIndex, "scope"]
      );
    }
  });

  const allMetrics = [
    ...review.results,
    ...decomposition.map(([metric]) => metric),
    ...review.factors.map((factor) => factor.comparison),
    ...review.salesStructure.attachMetrics.map((metric) => metric.comparison),
    ...review.employees.flatMap((employee) => [
      employee.metrics.completedSales,
      employee.metrics.netRevenue,
      employee.metrics.additionalRevenue,
      employee.metrics.additionalShare,
      employee.metrics.shiftCount,
      employee.metrics.workedHours,
      employee.metrics.revenuePerHour,
      ...employee.metrics.attachMetrics.map((metric) => metric.comparison)
    ])
  ];
  const structureNodes: WeeklyReviewStructureNode[] = [];
  const visitStructure = (node: WeeklyReviewStructureNode) => {
    structureNodes.push(node);
    allMetrics.push(node.comparison, node.shareComparison);
    node.children.forEach(visitStructure);
  };
  visitStructure(review.salesStructure.root);

  allMetrics.forEach((metric) => {
    if (metric.absoluteDelta !== null && (metric.current === null || metric.previous === null)) {
      addIssue("Metric delta requires current and previous values", ["evidence"]);
    }
    if (metric.materiality === "MATERIAL" && metric.sufficiency !== "SUFFICIENT") {
      addIssue("Material metric must have sufficient data", ["evidence"]);
    }
    if (metric.metricState === "UNAVAILABLE" && metric.current !== null && metric.previous !== null) {
      addIssue("Unavailable metric must have a missing value", ["evidence"]);
    }
  });

  const evidenceCounts = new Map<string, number>();
  review.evidence.forEach((item) => {
    evidenceCounts.set(item.evidenceRef, (evidenceCounts.get(item.evidenceRef) ?? 0) + 1);
  });
  for (const [reference, count] of evidenceCounts) {
    if (count !== 1) addIssue(`Evidence ${reference} is not unique`, ["evidence"]);
  }

  const referenced: string[] = [];
  const appendRefs = (value: { evidenceRefs: readonly string[] } | null) => {
    if (value) referenced.push(...value.evidenceRefs);
  };
  appendRefs(review.summary.outcome);
  appendRefs(review.summary.positive);
  appendRefs(review.summary.risk);
  allMetrics.forEach(appendRefs);
  review.factors.forEach(appendRefs);
  review.team.observations.forEach(appendRefs);
  review.actions.forEach(appendRefs);
  review.limitations.forEach(appendRefs);
  review.employees.forEach((employee) => {
    employee.ownDynamics.forEach(appendRefs);
    appendRefs(employee.peerComparison);
    appendRefs(employee.strength);
    appendRefs(employee.attention);
    appendRefs(employee.action);
  });
  referenced.forEach((reference) => {
    if (evidenceCounts.get(reference) !== 1) {
      addIssue(`Referenced weekly-review evidence is missing or ambiguous: ${reference}`, ["evidence"]);
    }
  });

  const unique = (values: string[], message: string, path: PropertyKey[]) => {
    if (new Set(values).size !== values.length) addIssue(message, path);
  };
  unique(review.factors.map((factor) => factor.factorId), "Factor IDs must be unique", ["factors"]);
  unique(
    [...review.actions, ...review.employees.flatMap((employee) => employee.action ? [employee.action] : [])]
      .map((action) => action.actionId),
    "Action IDs must be unique",
    ["actions"]
  );
  unique(review.employees.map((employee) => employee.employeePublicId), "Employee IDs must be unique", ["employees"]);
  unique(structureNodes.map((node) => node.nodeId), "Structure node IDs must be unique", ["salesStructure"]);
});

export type WeeklyReview = z.infer<typeof weeklyReviewSchema>;
export type WeeklyReviewMetric = z.infer<typeof weeklyReviewMetricSchema>;
export type WeeklyReviewAction = z.infer<typeof actionSchema>;
export type WeeklyReviewObservation = z.infer<typeof observationSchema>;
export type WeeklyReviewEvidence = WeeklyReview["evidence"][number];
export type WeeklyReviewEmployee = WeeklyReview["employees"][number];
