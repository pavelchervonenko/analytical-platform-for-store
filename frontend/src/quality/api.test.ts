import { afterEach, describe, expect, it, vi } from "vitest";
import { getStorePeriodQuality } from "./api";

const storeId = "8c967783-957e-48eb-9b94-ff27a3581508";

describe("period quality API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("accepts an absent plan with a null formula version", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      storeId,
      periodMonth: "2026-07-01",
      periodStart: "2026-07-01",
      periodEnd: "2026-07-31",
      asOfDate: "2026-07-20",
      status: "ERROR",
      readyForDecisions: false,
      areas: [],
      sourceData: {
        freshnessStatus: "CURRENT",
        dataThroughDate: "2026-07-20",
        completeThroughAsOf: true,
        classificationComplete: true,
        costDataComplete: true,
        includedItemCount: 10,
        unmappedItemCount: 0,
        missingCostItemCount: 0,
        unexpectedZeroCostItemCount: 0,
        openQualityIssueCount: 0
      },
      storePlan: {
        planPresent: false,
        inputDataCompleteThroughAsOf: true,
        classificationComplete: true,
        unmappedItemCount: 0,
        openQualityIssueCount: 0,
        formulaVersion: null
      },
      employeeRating: {
        planCoverageComplete: false,
        employeeCount: 0,
        eligibleEmployeeCount: 0,
        employeeWithShiftCount: 0,
        rankedEmployeeCount: 0,
        salesWithoutShiftCount: 0,
        insufficientScoreCoverageCount: 0,
        historyStatus: "LIVE",
        formulaVersion: "employee-rating-v1"
      },
      payroll: {
        readinessStatus: "NOT_READY",
        canCalculate: false,
        canApprove: false,
        planPresent: false,
        schemePresent: false,
        salesDayCount: 1,
        scheduledDayCount: 0,
        unmappedItemCount: 0,
        missingCostItemCount: 0,
        daysWithoutShift: 1,
        calculated: false,
        runStatus: null,
        freshness: null
      },
      issues: [],
      checkedAt: "2026-07-20T12:00:00Z"
    }), { status: 200, headers: { "Content-Type": "application/json" } })));

    const result = await getStorePeriodQuality(storeId, "2026-07", "2026-07-20");

    expect(result.storePlan.formulaVersion).toBeNull();
  });
});
