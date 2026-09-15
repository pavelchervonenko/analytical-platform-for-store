import { describe, expect, it } from "vitest";
import { makeWeeklyReview } from "../test/weeklyReviewFixture";
import { weeklyReviewSchema } from "./weeklyReviewContract";

function makeBlockedReview() {
  const review = makeWeeklyReview();
  review.reportState = "BLOCKED";
  review.qualitySummary.blockingCount = 1;
  review.summary.state = "INSUFFICIENT";
  review.summary.outcome = null;
  review.summary.positive = null;
  review.summary.risk = null;
  review.factors = [];
  review.actions = [];
  review.salesStructure.state = "INSUFFICIENT";
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
  const coreMetrics = [
    ...review.results,
    review.revenueDecomposition.salesRevenue,
    review.revenueDecomposition.returnRevenue,
    review.revenueDecomposition.netRevenue,
    review.revenueDecomposition.saleDocumentCount,
    review.revenueDecomposition.returnDocumentCount,
    review.salesStructure.root.comparison,
    review.salesStructure.root.shareComparison
  ];
  coreMetrics.forEach((metric) => {
    metric.current = null;
    metric.previous = null;
    metric.absoluteDelta = null;
    metric.changePercent = null;
    metric.comparisonKind = "UNAVAILABLE";
    metric.direction = "UNKNOWN";
    metric.effect = "UNKNOWN";
    metric.metricState = "UNAVAILABLE";
    metric.sufficiency = "INSUFFICIENT";
    metric.materiality = "NOT_EVALUATED";
    metric.currentSample = null;
    metric.previousSample = null;
  });
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
  const allowedEvidenceRefs = new Set(
    coreMetrics.flatMap((metric) => metric.evidenceRefs)
  );
  review.evidence = review.evidence.filter((item) => (
    allowedEvidenceRefs.has(item.evidenceRef)
  ));
  return review;
}

describe("weeklyReviewSchema", () => {
  it("accepts the v2 golden response serialized by the backend assembler", () => {
    const review = makeWeeklyReview();

    expect(review.contractVersion).toBe(2);
    expect(review.results.map((metric) => metric.code)).toEqual([
      "NET_REVENUE",
      "GROSS_PROFIT",
      "MARGIN_PERCENT",
      "AVERAGE_SALE"
    ]);
    expect(review.actions[0]).toMatchObject({
      priority: "HIGH",
      actionType: "RESTORE_METRIC",
      scope: "STORE"
    });
  });

  it("accepts both peer metrics during the compatible transition", () => {
    const review = makeWeeklyReview();
    expect(weeklyReviewSchema.safeParse(review).success).toBe(true);

    review.employees[0]!.peerComparison!.metricCode = "NET_REVENUE";
    expect(weeklyReviewSchema.safeParse(review).success).toBe(true);
  });

  it("requires a valid base for a revenue-per-hour peer comparison", () => {
    const review = makeWeeklyReview();
    review.employees[0]!.participatesInBenchmark = false;

    expect(() => weeklyReviewSchema.parse(review)).toThrow(
      "Revenue-per-hour peer comparison requires a ready eligible employee metric"
    );
  });

  it("rejects a different core metric order or unit", () => {
    const reordered = makeWeeklyReview();
    reordered.results = [
      reordered.results[1]!,
      reordered.results[0]!,
      reordered.results[2]!,
      reordered.results[3]!
    ];
    expect(() => weeklyReviewSchema.parse(reordered)).toThrow(
      "Core weekly metrics have an unexpected code, unit or order"
    );

    const wrongUnit = makeWeeklyReview();
    wrongUnit.results[0]!.unit = "COUNT";
    expect(weeklyReviewSchema.safeParse(wrongUnit).success).toBe(false);
  });

  it("rejects references missing from evidence in nested metrics", () => {
    const review = makeWeeklyReview();
    review.salesStructure.root.children[0]!.comparison.evidenceRefs = ["MISSING.EVIDENCE"];

    expect(() => weeklyReviewSchema.parse(review)).toThrow(
      "Referenced weekly-review evidence is missing or ambiguous"
    );
  });

  it("rejects employee content that references another employee's evidence", () => {
    const review = makeWeeklyReview();
    review.employees[1]!.metrics.netRevenue.evidenceRefs = [
      review.employees[0]!.metrics.netRevenue.evidenceRefs[0]!
    ];

    expect(() => weeklyReviewSchema.parse(review)).toThrow(
      "Employee content must reference evidence owned by the same employee"
    );
  });

  it("rejects malformed or non-adjacent completed weeks", () => {
    const shortWeek = makeWeeklyReview();
    shortWeek.period.current.end = "2026-08-22";
    expect(() => weeklyReviewSchema.parse(shortWeek)).toThrow(
      "Weekly period must contain exactly seven days"
    );

    const gap = makeWeeklyReview();
    gap.period.previous.start = "2026-08-03";
    gap.period.previous.end = "2026-08-09";
    expect(() => weeklyReviewSchema.parse(gap)).toThrow(
      "Current and previous weekly periods must be adjacent"
    );
  });

  it("recomputes the revenue identity for both periods", () => {
    const currentMismatch = makeWeeklyReview();
    currentMismatch.revenueDecomposition.netRevenue.current! += 1;
    expect(() => weeklyReviewSchema.parse(currentMismatch)).toThrow(
      "Revenue decomposition must satisfy sales minus returns equals net revenue"
    );

    const previousMismatch = makeWeeklyReview();
    previousMismatch.revenueDecomposition.netRevenue.previous! += 1;
    expect(weeklyReviewSchema.safeParse(previousMismatch).success).toBe(false);
  });

  it("requires every factor to be ready, material and effect-consistent", () => {
    const immaterial = makeWeeklyReview();
    immaterial.factors[0]!.comparison.materiality = "NOT_MATERIAL";
    expect(() => weeklyReviewSchema.parse(immaterial)).toThrow(
      "Factors require a ready, sufficient and material comparison"
    );

    const mismatchedEffect = makeWeeklyReview();
    mismatchedEffect.factors[0]!.comparison.effect = "POSITIVE";
    expect(() => weeklyReviewSchema.parse(mismatchedEffect)).toThrow(
      "Factor effect must match its comparison effect"
    );
  });

  it("rejects invalid report and summary state combinations", () => {
    const readyWithoutOutcome = makeWeeklyReview();
    readyWithoutOutcome.summary.outcome = null;
    expect(() => weeklyReviewSchema.parse(readyWithoutOutcome)).toThrow(
      "READY and PARTIAL reviews require a summary outcome"
    );

    const blockedWithoutBlocker = makeWeeklyReview();
    blockedWithoutBlocker.reportState = "BLOCKED";
    expect(() => weeklyReviewSchema.parse(blockedWithoutBlocker)).toThrow(
      "BLOCKED review requires at least one blocker"
    );

    const blockedWithReadyCore = makeBlockedReview();
    blockedWithReadyCore.results[0]!.metricState = "READY";
    blockedWithReadyCore.results[0]!.current = 0;
    expect(() => weeklyReviewSchema.parse(blockedWithReadyCore)).toThrow(
      "BLOCKED review requires unavailable core metrics"
    );

    const blockedWithReadyStructureRoot = makeBlockedReview();
    blockedWithReadyStructureRoot.salesStructure.root.comparison.metricState = "READY";
    expect(() => weeklyReviewSchema.parse(blockedWithReadyStructureRoot)).toThrow(
      "BLOCKED review requires unavailable core metrics"
    );

    const blockedWithStructureChildren = makeBlockedReview();
    blockedWithStructureChildren.salesStructure.root.children = [
      makeWeeklyReview().salesStructure.root.children[0]!
    ];
    expect(() => weeklyReviewSchema.parse(blockedWithStructureChildren)).toThrow(
      "BLOCKED review must not expose detailed analytics"
    );

    const blockedWithRosterAnalytics = makeBlockedReview();
    blockedWithRosterAnalytics.team.roster.activeAssignedWithActivity = 1;
    expect(() => weeklyReviewSchema.parse(blockedWithRosterAnalytics)).toThrow(
      "BLOCKED review must not expose detailed analytics"
    );

    const blockedWithAvailableEvidence = makeBlockedReview();
    const netRevenueRef = blockedWithAvailableEvidence.results[0]!.evidenceRefs[0]!;
    blockedWithAvailableEvidence.evidence
      .find((item) => item.evidenceRef === netRevenueRef)!.available = true;
    expect(() => weeklyReviewSchema.parse(blockedWithAvailableEvidence)).toThrow(
      "BLOCKED review requires unavailable detailed evidence"
    );

    const blockedWithOrphanEmployeeEvidence = makeBlockedReview();
    blockedWithOrphanEmployeeEvidence.evidence.push({
      ...makeWeeklyReview().evidence.find((item) => item.scope === "EMPLOYEE")!,
      currentValue: null,
      previousValue: null,
      currentNumerator: null,
      currentDenominator: null,
      previousNumerator: null,
      previousDenominator: null,
      sufficiency: "INSUFFICIENT",
      materiality: "NOT_EVALUATED",
      available: false
    });
    expect(() => weeklyReviewSchema.parse(blockedWithOrphanEmployeeEvidence)).toThrow(
      "BLOCKED review requires unavailable detailed evidence"
    );

    expect(weeklyReviewSchema.safeParse(makeBlockedReview()).success).toBe(true);

    const legacyBlocked = makeWeeklyReview();
    legacyBlocked.versions.metricsPolicy = "weekly-metrics-v5";
    legacyBlocked.reportState = "BLOCKED";
    legacyBlocked.qualitySummary.blockingCount = 1;
    legacyBlocked.summary.state = "INSUFFICIENT";
    legacyBlocked.summary.outcome = null;
    legacyBlocked.summary.positive = null;
    legacyBlocked.summary.risk = null;
    legacyBlocked.factors = [];
    legacyBlocked.actions = [];
    expect(weeklyReviewSchema.safeParse(legacyBlocked).success).toBe(true);

    const readyWithLimitedTeam = makeWeeklyReview();
    readyWithLimitedTeam.team.state = "LIMITED";
    expect(() => weeklyReviewSchema.parse(readyWithLimitedTeam)).toThrow(
      "READY review requires ready local blocks and no quality issues"
    );

    const partialWithoutReason = makeWeeklyReview();
    partialWithoutReason.reportState = "PARTIAL";
    expect(() => weeklyReviewSchema.parse(partialWithoutReason)).toThrow(
      "PARTIAL review requires a warning or a constrained local block and no blockers"
    );
  });

  it("accepts PARTIAL caused only by a constrained local block", () => {
    const review = makeWeeklyReview();
    review.reportState = "PARTIAL";
    review.summary.state = "LIMITED";
    review.team.state = "LIMITED";

    expect(weeklyReviewSchema.safeParse(review).success).toBe(true);
  });

  it("rejects unknown action enums and a mismatched employee scope", () => {
    const unknownPriority = makeWeeklyReview();
    (unknownPriority.actions[0] as { priority: string }).priority = "P1";
    expect(weeklyReviewSchema.safeParse(unknownPriority).success).toBe(false);

    const mismatchedScope = makeWeeklyReview();
    mismatchedScope.actions[0]!.scope = "EMPLOYEE";
    expect(() => weeklyReviewSchema.parse(mismatchedScope)).toThrow(
      "Employee action scope must match employeePublicId"
    );

    const employeeRootAction = makeWeeklyReview();
    employeeRootAction.actions[0]!.scope = "EMPLOYEE";
    employeeRootAction.actions[0]!.employeePublicId = employeeRootAction.employees[0]!.employeePublicId;
    expect(() => weeklyReviewSchema.parse(employeeRootAction)).toThrow(
      "Root actions must target the store or team"
    );

    const wrongEmployee = makeWeeklyReview();
    wrongEmployee.employees[0]!.action = {
      ...wrongEmployee.actions[0]!,
      actionId: "employee-action:test",
      scope: "EMPLOYEE",
      employeePublicId: wrongEmployee.employees[1]!.employeePublicId
    };
    expect(() => weeklyReviewSchema.parse(wrongEmployee)).toThrow(
      "Employee action must target its owning employee"
    );
  });

  it("requires every root action to match one negative factor", () => {
    const review = makeWeeklyReview();
    review.actions[0]!.metricCode = "DEVICES_REVENUE";
    review.actions[0]!.evidenceRefs = ["STORE.STRUCTURE.DEVICES.REVENUE"];

    expect(() => weeklyReviewSchema.parse(review)).toThrow(
      "Every root action must match exactly one negative factor"
    );
  });
});
