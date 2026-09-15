import { describe, expect, it } from "vitest";
import { makeWeeklyReview } from "../../test/weeklyReviewFixture";
import {
  createWeeklyReviewViewModel,
  employeeEfficiencyComparison,
  employeeWorkloadNotice,
  evidenceFor
} from "./weeklyReviewViewModel";

describe("weeklyReviewViewModel", () => {
  it("uses backend order for the primary action and links it semantically", () => {
    const review = makeWeeklyReview();
    const model = createWeeklyReviewViewModel(review);

    expect(model.primaryAction?.actionId).toBe(review.actions[0]?.actionId);
    expect(model.primaryActionFactor?.comparison.code).toBe(model.primaryAction?.metricCode);
    expect(model.secondaryActions).toEqual(review.actions.slice(1));
    expect(model.secondaryFactors.map((factor) => factor.factorId))
      .toEqual(review.factors.slice(1).map((factor) => factor.factorId));
    expect(model.hasMaterialFactors).toBe(true);
  });

  it("keeps all factors visible when the primary relation is ambiguous", () => {
    const review = makeWeeklyReview();
    review.factors.push({
      ...structuredClone(review.factors[0]!),
      factorId: "factor:return_revenue:duplicate"
    });

    const model = createWeeklyReviewViewModel(review);

    expect(model.primaryActionFactor).toBeNull();
    expect(model.secondaryFactors).toEqual(review.factors);
  });

  it("distinguishes a calm review from a review whose only factor moved to the decision block", () => {
    const review = makeWeeklyReview();
    review.factors = [review.factors[0]!];

    const decisionOnly = createWeeklyReviewViewModel(review);
    expect(decisionOnly.hasMaterialFactors).toBe(true);
    expect(decisionOnly.secondaryFactors).toEqual([]);

    review.factors = [];
    const calm = createWeeklyReviewViewModel(review);
    expect(calm.hasMaterialFactors).toBe(false);
    expect(calm.secondaryFactors).toEqual([]);
  });

  it("shows at most three real attention employees", () => {
    const review = makeWeeklyReview();
    const template = review.employees[0]!;
    review.employees = Array.from({ length: 5 }, (_, index) => ({
      ...structuredClone(template),
      employeePublicId: `attention-${index}`,
      displayName: `Сотрудник ${index}`,
      sortGroup: "ATTENTION" as const,
      attention: structuredClone(template.ownDynamics[0]!)
    }));

    const model = createWeeklyReviewViewModel(review);

    expect(model.attentionEmployees).toHaveLength(3);
    expect(model.attentionEmployees.map((employee) => employee.employeePublicId))
      .toEqual(["attention-0", "attention-1", "attention-2"]);
  });

  it("does not turn an old net-revenue peer payload into efficiency", () => {
    const review = makeWeeklyReview();
    const employee = review.employees[0]!;

    employee.peerComparison!.metricCode = "NET_REVENUE";
    expect(employee.peerComparison?.metricCode).toBe("NET_REVENUE");
    expect(employeeEfficiencyComparison(employee)).toBeNull();

    employee.peerComparison!.metricCode = "REVENUE_PER_HOUR";
    expect(employeeEfficiencyComparison(employee)).not.toBeNull();
  });

  it("filters workload-only limitations from the page warning", () => {
    const review = makeWeeklyReview();
    review.limitations = [{
      limitationId: "missing-shifts",
      code: "WORKLOAD_DATA_MISSING",
      severity: "WARNING",
      scope: "EMPLOYEE",
      employeePublicId: review.employees[0]!.employeePublicId,
      affectedBlockIds: ["employees"],
      affectedMetricCodes: ["WORKED_HOURS", "REVENUE_PER_HOUR"],
      period: structuredClone(review.period.current),
      affectedCount: 1,
      summary: "Не заполнены смены",
      resolution: "Заполните смены для расчёта эффективности.",
      evidenceRefs: []
    }];

    expect(createWeeklyReviewViewModel(review).limitations).toEqual([]);
  });

  it("explains unavailable time efficiency without changing the employee group", () => {
    const review = makeWeeklyReview();
    const employee = review.employees[0]!;
    employee.metrics.shiftCount.metricState = "LIMITED";
    employee.metrics.shiftCount.sufficiency = "LIMITED";
    employee.metrics.workedHours.metricState = "UNAVAILABLE";
    employee.metrics.workedHours.sufficiency = "INSUFFICIENT";
    employee.metrics.revenuePerHour.metricState = "UNAVAILABLE";
    employee.metrics.revenuePerHour.sufficiency = "INSUFFICIENT";
    employee.metrics.revenuePerHour.current = null;

    expect(employeeWorkloadNotice(employee))
      .toBe("Часть смен не заполнена — оценка по часам недоступна");
    expect(employee.sortGroup).toBe("POSITIVE");
  });

  it("ignores missing evidence references safely", () => {
    const review = makeWeeklyReview();
    const model = createWeeklyReviewViewModel(review);

    expect(evidenceFor(["missing"], model.evidenceByRef)).toEqual([]);
  });
});
