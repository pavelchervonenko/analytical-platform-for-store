import type {
  WeeklyReview,
  WeeklyReviewEmployee,
  WeeklyReviewMetric,
  WeeklyReviewStructureNode
} from "../src/api/weeklyReviewContract";
import { makeWeeklyReview } from "../src/test/weeklyReviewFixture";

function unavailableWorkloadMetric(metric: WeeklyReviewMetric): WeeklyReviewMetric {
  return {
    ...metric,
    current: null,
    previous: null,
    absoluteDelta: null,
    changePercent: null,
    comparisonKind: "NO_BASE",
    direction: "UNKNOWN",
    effect: "NEUTRAL",
    metricState: "UNAVAILABLE",
    sufficiency: "INSUFFICIENT",
    materiality: "NOT_MATERIAL",
    currentSample: null,
    previousSample: null
  };
}

function unavailableBlockedMetric(metric: WeeklyReviewMetric): WeeklyReviewMetric {
  return {
    ...unavailableWorkloadMetric(metric),
    comparisonKind: "UNAVAILABLE",
    effect: "UNKNOWN",
    materiality: "NOT_EVALUATED"
  };
}

function neutralizeMetric(metric: WeeklyReviewMetric): void {
  metric.current = metric.previous;
  metric.absoluteDelta = metric.previous === null ? null : 0;
  metric.changePercent = metric.previous === null || metric.previous === 0 ? null : 0;
  metric.comparisonKind = metric.previous === null || metric.previous === 0
    ? "NO_BASE"
    : "PERCENT_AVAILABLE";
  metric.direction = metric.previous === null ? "UNKNOWN" : "FLAT";
  metric.effect = "NEUTRAL";
  metric.materiality = "NOT_MATERIAL";
  metric.currentSample = metric.previousSample === null
    ? null
    : structuredClone(metric.previousSample);
}

function neutralizeStructure(node: WeeklyReviewStructureNode): void {
  neutralizeMetric(node.comparison);
  neutralizeMetric(node.shareComparison);
  node.children.forEach(neutralizeStructure);
}

function reidentifyEmployee<T>(value: T, previousId: string, employeePublicId: string): T {
  return JSON.parse(
    JSON.stringify(value).replaceAll(previousId, employeePublicId)
  ) as T;
}

export function visualWeeklyReview(scenario: string | null): WeeklyReview {
  const review = makeWeeklyReview();
  if (scenario === "ready-calm") {
    review.results.forEach(neutralizeMetric);
    Object.values(review.revenueDecomposition)
      .filter((value): value is WeeklyReviewMetric => typeof value === "object")
      .forEach(neutralizeMetric);
    neutralizeStructure(review.salesStructure.root);
    review.salesStructure.attachMetrics.forEach((metric) => neutralizeMetric(metric.comparison));
    review.employees.forEach((employee) => {
      Object.values(employee.metrics).forEach((value) => {
        if (Array.isArray(value)) {
          value.forEach((metric) => neutralizeMetric(metric.comparison));
        } else {
          neutralizeMetric(value);
        }
      });
      employee.ownDynamics = [];
      employee.strength = null;
      employee.attention = null;
      employee.action = null;
      employee.sortGroup = "STABLE";
    });
    review.evidence.forEach((evidence) => {
      if (evidence.previousValue === null) return;
      evidence.currentValue = evidence.previousValue;
      evidence.currentNumerator = evidence.previousNumerator;
      evidence.currentDenominator = evidence.previousDenominator;
      evidence.materiality = "NOT_MATERIAL";
    });
    review.summary.outcome!.text = "Ключевые результаты недели существенно не изменились.";
    review.summary.outcome!.effect = "NEUTRAL";
    review.summary.positive = null;
    review.summary.risk = null;
    review.factors = [];
    review.actions = [];
    review.team.observations = [];
    review.team.attentionEmployeeCount = 0;
    return review;
  }

  if (scenario === "ready-missing-shifts") {
    review.employees = review.employees.map((employee) => ({
      ...employee,
      metrics: {
        ...employee.metrics,
        shiftCount: unavailableWorkloadMetric(employee.metrics.shiftCount),
        workedHours: unavailableWorkloadMetric(employee.metrics.workedHours),
        revenuePerHour: unavailableWorkloadMetric(employee.metrics.revenuePerHour)
      },
      peerComparison: null,
      limitations: []
    }));
    const employeeWithSalesSignal = review.employees[0]!;
    employeeWithSalesSignal.metrics.netRevenue = {
      ...employeeWithSalesSignal.metrics.netRevenue,
      current: 300,
      previous: 360,
      absoluteDelta: -60,
      changePercent: -16.67,
      comparisonKind: "PERCENT_AVAILABLE",
      direction: "DOWN",
      effect: "NEGATIVE",
      metricState: "READY",
      sufficiency: "SUFFICIENT",
      materiality: "MATERIAL"
    };
    employeeWithSalesSignal.attention = {
      observationId: "visual:missing-shifts:sales-attention",
      title: "Снизилась чистая выручка",
      detail: "Чистая выручка снизилась относительно прошлой недели.",
      effect: "NEGATIVE",
      evidenceRefs: employeeWithSalesSignal.metrics.netRevenue.evidenceRefs
    };
    employeeWithSalesSignal.ownDynamics = [employeeWithSalesSignal.attention];
    employeeWithSalesSignal.sortGroup = "ATTENTION";
    employeeWithSalesSignal.action = null;
    review.team.roster.participatesInBenchmark = 0;
    review.team.roster.excludedFromBenchmark = review.employees.length;
    review.team.attentionEmployeeCount = 1;
    review.team.benchmarkPolicy.label = "Медиана выручки в час, минимум 3 сотрудника";
    review.limitations = [{
      limitationId: "visual:workload-missing",
      code: "WORKLOAD_DATA_MISSING",
      severity: "WARNING",
      scope: "EMPLOYEE",
      employeePublicId: review.employees[0]!.employeePublicId,
      affectedBlockIds: ["employees"],
      affectedMetricCodes: ["SHIFT_COUNT", "WORKED_HOURS", "REVENUE_PER_HOUR"],
      period: structuredClone(review.period.current),
      affectedCount: review.employees.length,
      summary: "Для части сотрудников не заполнены смены.",
      resolution: "Заполните смены, если требуется сравнение эффективности.",
      evidenceRefs: []
    }];
    return review;
  }

  if (scenario === "partial") {
    review.reportState = "PARTIAL";
    review.salesStructure.state = "LIMITED";
    review.qualitySummary = {
      blockingCount: 0,
      warningCount: 2,
      affectedBlockCount: 2,
      message: "Основные показатели доступны; валовая прибыль и структура требуют уточнения."
    };
    review.limitations = [
      {
        limitationId: "classification:current",
        code: "PRODUCTS_UNCLASSIFIED",
        severity: "WARNING",
        scope: "STORE",
        employeePublicId: null,
        affectedBlockIds: ["sales-structure"],
        affectedMetricCodes: ["SALES_STRUCTURE", "ATTACH"],
        period: structuredClone(review.period.current),
        affectedCount: 7,
        summary: "Часть товарных позиций недели не классифицирована.",
        resolution: null,
        evidenceRefs: []
      },
      {
        limitationId: "cost:missing:current",
        code: "COST_DATA_MISSING",
        severity: "WARNING",
        scope: "STORE",
        employeePublicId: null,
        affectedBlockIds: ["results"],
        affectedMetricCodes: ["GROSS_PROFIT", "MARGIN_PERCENT"],
        period: structuredClone(review.period.current),
        affectedCount: 4,
        summary: "Для части позиций недели отсутствует себестоимость.",
        resolution: null,
        evidenceRefs: []
      }
    ];
    review.sourceCoverage.forEach((source) => {
      if (source.sourceCode === "CLASSIFICATION" || source.sourceCode === "COST") {
        source.state = "PARTIAL";
        source.message = "Источник требует уточнения";
      }
    });
    return review;
  }

  if (scenario === "blocked") {
    review.reportState = "BLOCKED";
    review.summary.state = "INSUFFICIENT";
    review.summary.outcome = null;
    review.summary.positive = null;
    review.summary.risk = null;
    review.results = review.results.map(unavailableBlockedMetric);
    review.revenueDecomposition.salesRevenue = unavailableBlockedMetric(
      review.revenueDecomposition.salesRevenue
    );
    review.revenueDecomposition.returnRevenue = unavailableBlockedMetric(
      review.revenueDecomposition.returnRevenue
    );
    review.revenueDecomposition.netRevenue = unavailableBlockedMetric(
      review.revenueDecomposition.netRevenue
    );
    review.revenueDecomposition.saleDocumentCount = unavailableBlockedMetric(
      review.revenueDecomposition.saleDocumentCount
    );
    review.revenueDecomposition.returnDocumentCount = unavailableBlockedMetric(
      review.revenueDecomposition.returnDocumentCount
    );
    review.salesStructure.state = "INSUFFICIENT";
    review.salesStructure.root.comparison = unavailableBlockedMetric(
      review.salesStructure.root.comparison
    );
    review.salesStructure.root.shareComparison = unavailableBlockedMetric(
      review.salesStructure.root.shareComparison
    );
    review.salesStructure.root.children = [];
    review.salesStructure.attachMetrics = [];
    review.team.state = "INSUFFICIENT";
    Object.assign(review.team.roster, {
      activeAssignedWithActivity: 0,
      participatesInBenchmark: 0,
      sufficientByAnyMetric: 0,
      limitedOrInsufficient: 0,
      excludedFromBenchmark: 0
    });
    review.team.observations = [];
    review.team.attentionEmployeeCount = 0;
    review.employees = [];
    review.factors = [];
    review.actions = [];
    const blockedMetrics = [
      ...review.results,
      review.revenueDecomposition.salesRevenue,
      review.revenueDecomposition.returnRevenue,
      review.revenueDecomposition.netRevenue,
      review.revenueDecomposition.saleDocumentCount,
      review.revenueDecomposition.returnDocumentCount,
      review.salesStructure.root.comparison,
      review.salesStructure.root.shareComparison
    ];
    const allowedEvidenceRefs = new Set(
      blockedMetrics.flatMap((metric) => metric.evidenceRefs)
    );
    review.evidence = review.evidence.filter((item) => (
      allowedEvidenceRefs.has(item.evidenceRef)
    ));
    review.evidence.forEach((item) => {
      item.currentValue = null;
      item.previousValue = null;
      item.currentNumerator = null;
      item.currentDenominator = null;
      item.previousNumerator = null;
      item.previousDenominator = null;
      item.sufficiency = "INSUFFICIENT";
      item.materiality = "NOT_EVALUATED";
      item.available = false;
    });
    review.qualitySummary = {
      blockingCount: 1,
      warningCount: 0,
      affectedBlockCount: 3,
      message: "Не загружены продажи за часть завершённой недели."
    };
    review.limitations = [{
      limitationId: "visual:sales-missing",
      code: "SALES_MISSING",
      severity: "BLOCKING",
      scope: "STORE",
      employeePublicId: null,
      affectedBlockIds: ["summary", "results", "sales-structure"],
      affectedMetricCodes: ["NET_REVENUE", "GROSS_PROFIT"],
      period: structuredClone(review.period.current),
      affectedCount: 1,
      summary: "Продажи загружены не за всю неделю.",
      resolution: "Восстановите синхронизацию продаж и пересчитайте разбор.",
      evidenceRefs: []
    }];
    return review;
  }

  const returnFactor = review.factors[0]!;
  const deviceFactor = structuredClone(review.factors[1]!);
  Object.assign(deviceFactor, {
    title: "Выручка направления «Техника» снизилась относительно прошлой недели",
    detail: "Техника: 500 ₽ против 600 ₽ (−16.7%)",
    effect: "NEGATIVE" as const
  });
  Object.assign(deviceFactor.comparison, {
    current: 500,
    previous: 600,
    absoluteDelta: -100,
    changePercent: -16.67,
    direction: "DOWN" as const,
    effect: "NEGATIVE" as const
  });
  const additionalNode = review.salesStructure.root.children.find(
    (node) => node.code === "ADDITIONAL_REVENUE"
  )!;
  const additionalFactor = {
    ...structuredClone(deviceFactor),
    factorId: "factor:additional_revenue_revenue",
    title: "Дополнительная выручка снизилась относительно прошлой недели",
    detail: "Дополнительная выручка: 150 ₽ против 250 ₽ (−40.0%)",
    comparison: {
      ...structuredClone(additionalNode.comparison),
      current: 150,
      previous: 250,
      absoluteDelta: -100,
      changePercent: -40,
      direction: "DOWN" as const,
      effect: "NEGATIVE" as const,
      materiality: "MATERIAL" as const
    },
    evidenceRefs: additionalNode.comparison.evidenceRefs
  };
  review.factors = [returnFactor, deviceFactor, additionalFactor];
  const deviceNode = review.salesStructure.root.children.find((node) => node.code === "DEVICES")!;
  deviceNode.comparison = structuredClone(deviceFactor.comparison);
  additionalNode.comparison = structuredClone(additionalFactor.comparison);
  const updateEvidence = (reference: string, currentValue: number, previousValue: number) => {
    const evidence = review.evidence.find((item) => item.evidenceRef === reference)!;
    evidence.currentValue = currentValue;
    evidence.previousValue = previousValue;
    evidence.materiality = "MATERIAL";
  };
  updateEvidence(deviceFactor.evidenceRefs[0]!, 500, 600);
  updateEvidence(additionalFactor.evidenceRefs[0]!, 150, 250);

  const templateAction = review.actions[0]!;
  review.actions = [
    templateAction,
    {
      ...structuredClone(templateAction),
      actionId: "action:visual:restore-devices",
      title: "Сверить снижение техники по категориям и продавцам",
      metricCode: deviceFactor.comparison.code,
      target: { operator: "AT_LEAST", value: 600, unit: "RUB" },
      check: "Сравнить выручку техники следующей полной недели с 600 ₽",
      evidenceRefs: deviceFactor.evidenceRefs
    },
    {
      ...structuredClone(templateAction),
      actionId: "action:visual:restore-additional",
      title: "Проверить просадку аксессуаров и услуг по чекам",
      metricCode: additionalFactor.comparison.code,
      target: { operator: "AT_LEAST", value: 250, unit: "RUB" },
      check: "Сравнить дополнительную выручку следующей полной недели с 250 ₽",
      evidenceRefs: additionalFactor.evidenceRefs
    }
  ];
  const employeeTemplates = review.employees;
  const sourceEmployeeEvidence = review.evidence.filter((item) => item.scope === "EMPLOYEE");
  const denseEmployeeEvidence: WeeklyReview["evidence"] = [];
  review.employees = Array.from({ length: 10 }, (_, index) => {
    const employeePublicId = `30000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`;
    const needsAttention = index < 3;
    const employeeTemplate = employeeTemplates[index % employeeTemplates.length]!;
    const employee = reidentifyEmployee<WeeklyReviewEmployee>(
      employeeTemplate,
      employeeTemplate.employeePublicId,
      employeePublicId
    );
    sourceEmployeeEvidence
      .filter((item) => item.employeePublicId === employeeTemplate.employeePublicId)
      .forEach((item) => {
        denseEmployeeEvidence.push(reidentifyEmployee(
          item,
          employeeTemplate.employeePublicId,
          employeePublicId
        ));
      });
    const revenuePerHourRef = employee.metrics.revenuePerHour.evidenceRefs[0]!;
    if (needsAttention) {
      Object.assign(employee.metrics.revenuePerHour, {
        current: 20,
        previous: 28,
        absoluteDelta: -8,
        changePercent: -28.57,
        direction: "DOWN" as const,
        effect: "NEGATIVE" as const,
        currentSample: {
          numerator: 280,
          denominator: 14,
          numeratorLabel: "Выручка",
          denominatorLabel: "Отработанные часы"
        },
        previousSample: {
          numerator: 392,
          denominator: 14,
          numeratorLabel: "Выручка",
          denominatorLabel: "Отработанные часы"
        }
      });
      const evidence = denseEmployeeEvidence.find((item) => item.evidenceRef === revenuePerHourRef)!;
      Object.assign(evidence, {
        currentValue: 20,
        previousValue: 28,
        currentNumerator: 280,
        currentDenominator: 14,
        previousNumerator: 392,
        previousDenominator: 14,
        materiality: "MATERIAL" as const
      });
    }
    return {
      ...employee,
      employeePublicId,
      displayName: [
        "Ковзель Ангелина Александровна",
        "Баглык Даниил Константинович",
        "Думнов Алексей Владимирович"
      ][index] ?? `Сотрудник стабильной группы № ${index + 1}`,
      sortGroup: needsAttention ? "ATTENTION" as const : "POSITIVE" as const,
      ownDynamics: needsAttention ? [{
        observationId: `visual:employee:${index}:revenue-per-hour`,
        title: "Выручка в час снизилась",
        detail: "Текущая неделя: 20 ₽; предыдущая: 28 ₽",
        effect: "NEGATIVE" as const,
        evidenceRefs: [revenuePerHourRef]
      }] : employee.ownDynamics,
      strength: needsAttention ? null : employee.strength,
      attention: needsAttention ? {
        observationId: `visual:employee:${index}:attention`,
        title: "Снизилась выручка в час",
        detail: "Текущая неделя: 20 ₽/ч; предыдущая: 28 ₽/ч.",
        effect: "NEGATIVE" as const,
        evidenceRefs: [revenuePerHourRef]
      } : null,
      peerComparison: employee.peerComparison === null ? null : {
        ...employee.peerComparison,
        metricCode: "REVENUE_PER_HOUR" as const,
        employeeValue: needsAttention ? 20 : employee.peerComparison.employeeValue,
        benchmarkValue: 25,
        eligibleCount: 10,
        absoluteDelta: needsAttention ? -5 : employee.peerComparison.employeeValue - 25,
        changePercent: needsAttention ? -20 : employee.peerComparison.changePercent,
        effect: needsAttention ? "NEGATIVE" as const : employee.peerComparison.effect
      },
      action: needsAttention ? {
        actionId: `action:visual:employee:${index}`,
        priority: "HIGH" as const,
        actionType: "RESTORE_METRIC" as const,
        scope: "EMPLOYEE" as const,
        employeePublicId,
        title: "Вернуть выручку в час к уровню прошлой недели",
        metricCode: "REVENUE_PER_HOUR",
        target: { operator: "AT_LEAST" as const, value: 28, unit: "RUB" as const },
        check: "Сравнить следующую полную неделю с 28 ₽/ч",
        horizon: "NEXT_FULL_WEEK" as const,
        generatedBy: "DETERMINISTIC" as const,
        evidenceRefs: [revenuePerHourRef]
      } : null
    };
  });
  review.evidence = [
    ...review.evidence.filter((item) => item.scope !== "EMPLOYEE"),
    ...denseEmployeeEvidence
  ];
  review.team.roster.activeAssignedWithActivity = 10;
  review.team.roster.participatesInBenchmark = 10;
  review.team.roster.sufficientByAnyMetric = 10;
  review.team.attentionEmployeeCount = 3;
  review.team.benchmarkPolicy.label = "Медиана выручки в час, 10 сотрудников";
  return review;
}

