import type {
  WeeklyReview,
  WeeklyReviewAction,
  WeeklyReviewEmployee,
  WeeklyReviewEvidence,
  WeeklyReviewMetric
} from "../../api/weeklyReviewContract";

const WORKLOAD_METRICS = new Set(["SHIFT_COUNT", "WORKED_HOURS", "REVENUE_PER_HOUR"]);

export type EvidenceIndex = ReadonlyMap<string, WeeklyReviewEvidence>;

export type WeeklyReviewViewModel = {
  outcome: WeeklyReview["summary"]["outcome"];
  primaryAction: WeeklyReviewAction | null;
  secondaryActions: WeeklyReviewAction[];
  primaryActionFactor: WeeklyReview["factors"][number] | null;
  secondaryFactors: WeeklyReview["factors"];
  hasMaterialFactors: boolean;
  attentionEmployees: WeeklyReviewEmployee[];
  limitations: WeeklyReview["limitations"];
  evidenceByRef: EvidenceIndex;
};

export function createWeeklyReviewViewModel(review: WeeklyReview): WeeklyReviewViewModel {
  const primaryAction = review.actions[0] ?? null;
  const relatedFactors = primaryAction === null
    ? []
    : review.factors.filter((factor) => (
      factor.comparison.code === primaryAction.metricCode
      && primaryAction.evidenceRefs.every((reference) => factor.evidenceRefs.includes(reference))
    ));

  const primaryActionFactor = relatedFactors.length === 1 ? relatedFactors[0]! : null;

  return {
    outcome: review.summary.outcome,
    primaryAction,
    secondaryActions: review.actions.slice(1),
    primaryActionFactor,
    secondaryFactors: primaryActionFactor === null
      ? review.factors
      : review.factors.filter((factor) => factor.factorId !== primaryActionFactor.factorId),
    hasMaterialFactors: review.factors.length > 0,
    attentionEmployees: review.employees
      .filter((employee) => employee.sortGroup === "ATTENTION" && employee.attention !== null)
      .slice(0, 3),
    limitations: review.limitations.filter((limitation) => !isWorkloadOnlyLimitation(limitation)),
    evidenceByRef: new Map(review.evidence.map((item) => [item.evidenceRef, item]))
  };
}

export function evidenceFor(
  references: readonly string[],
  evidenceByRef: EvidenceIndex
): WeeklyReviewEvidence[] {
  return [...new Set(references)]
    .map((reference) => evidenceByRef.get(reference))
    .filter((item): item is WeeklyReviewEvidence => item !== undefined);
}

export function limitationsForBlock(
  limitations: WeeklyReview["limitations"],
  blockId: string
): WeeklyReview["limitations"] {
  return limitations.filter((limitation) => limitation.affectedBlockIds.includes(blockId));
}

export function limitationsForMetric(
  limitations: WeeklyReview["limitations"],
  metric: WeeklyReviewMetric
): WeeklyReview["limitations"] {
  return limitations.filter((limitation) => (
    limitation.affectedMetricCodes.includes(metric.code)
  ));
}

export function employeeEfficiencyComparison(
  employee: WeeklyReviewEmployee
): WeeklyReviewEmployee["peerComparison"] {
  return employee.peerComparison?.metricCode === "REVENUE_PER_HOUR"
    && employee.participatesInBenchmark
    && employee.metrics.revenuePerHour.metricState === "READY"
    && employee.metrics.revenuePerHour.sufficiency === "SUFFICIENT"
    && employee.metrics.revenuePerHour.current !== null
    ? employee.peerComparison
    : null;
}

export function employeeWorkloadNotice(employee: WeeklyReviewEmployee): string | null {
  const workloadMetrics = [employee.metrics.shiftCount, employee.metrics.workedHours];
  const workloadIncomplete = workloadMetrics.some((metric) => (
    metric.metricState !== "READY"
    || metric.sufficiency !== "SUFFICIENT"
    || metric.current === null
  ));
  const efficiencyUnavailable = employee.metrics.revenuePerHour.metricState !== "READY"
    || employee.metrics.revenuePerHour.sufficiency !== "SUFFICIENT"
    || employee.metrics.revenuePerHour.current === null;

  return workloadIncomplete && efficiencyUnavailable
    ? "Часть смен не заполнена — оценка по часам недоступна"
    : null;
}

export function isWorkloadMetric(metric: WeeklyReviewMetric): boolean {
  return WORKLOAD_METRICS.has(metric.code);
}

function isWorkloadOnlyLimitation(
  limitation: WeeklyReview["limitations"][number]
): boolean {
  return limitation.affectedMetricCodes.length > 0
    && limitation.affectedMetricCodes.every((code) => WORKLOAD_METRICS.has(code));
}
