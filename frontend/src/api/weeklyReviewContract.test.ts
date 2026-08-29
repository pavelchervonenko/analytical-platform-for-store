import { describe, expect, it } from "vitest";
import { makeWeeklyReview } from "../test/weeklyReviewFixture";
import { weeklyReviewSchema } from "./weeklyReviewContract";

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
});
