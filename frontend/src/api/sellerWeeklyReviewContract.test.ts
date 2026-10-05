import { describe, expect, it } from "vitest";
import { makeSellerWeeklyReview } from "../test/sellerWeeklyReviewFixture";
import { makeWeeklyReview } from "../test/weeklyReviewFixture";
import { sellerWeeklyReviewSchema, sellerWeeklyReviewViewSchema } from "./sellerWeeklyReviewContract";
import { weeklyReviewSchema } from "./weeklyReviewContract";

describe("seller weekly review v3", () => {
  it("accepts the backend-assembled seller golden without changing legacy parsing", () => {
    const report = makeSellerWeeklyReview();
    expect(report.scope).toBe("SELLERS");
    expect(report.reportState).toBe("PARTIAL");
    expect(report.employees[0]!.card.peerComparison).toBeNull();
    expect(weeklyReviewSchema.safeParse(report).success).toBe(false);
    expect(sellerWeeklyReviewSchema.safeParse(makeWeeklyReview()).success).toBe(false);
    expect(weeklyReviewSchema.safeParse(makeWeeklyReview()).success).toBe(true);
  });

  it("requires one current ranking cohort for both weeks and actionability", () => {
    const report = makeSellerWeeklyReview();
    report.membership.previousCohortHash = "f".repeat(64);
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
  });

  it("rejects legacy STORE evidence even if its value looks plausible", () => {
    const report = makeSellerWeeklyReview();
    report.evidence[0]!.evidenceRef = "STORE.NET_REVENUE";
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
  });

  it("accepts historical selection with a distinct present-day action roster", () => {
    const report = makeSellerWeeklyReview();
    report.membership.basis = "HISTORICAL_DOCUMENT_MEMBERSHIP_V1";
    report.membership.actionabilityRosterHash = "f".repeat(64);
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(true);
    report.membership.previousCohortHash = "e".repeat(64);
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
  });

  it("rejects future actions for a historical seller who is no longer actionable", () => {
    const report = makeSellerWeeklyReview();
    report.membership.basis = "HISTORICAL_DOCUMENT_MEMBERSHIP_V1";
    const employee = report.employees[0]!;
    employee.card.action = { actionId: "action:former", priority: "HIGH", actionType: "REVIEW_SELLER_METRIC",
      scope: "EMPLOYEE", employeePublicId: employee.card.employeePublicId, title: "Synthetic action",
      metricCode: "NET_REVENUE", target: { operator: "AT_LEAST", value: 1, unit: "RUB" },
      check: "Synthetic criterion", horizon: "NEXT_FULL_WEEK", generatedBy: "DETERMINISTIC",
      evidenceRefs: employee.card.metrics.netRevenue.evidenceRefs };
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(true);
    employee.actionableNow = false;
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
  });

  it("keeps monetary and percentage denominators separate", () => {
    const report = makeSellerWeeklyReview();
    report.additionalSales.shareOfSellerRevenue.code = "ADDITIONAL_REVENUE";
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
  });

  it("rejects composition sums that do not reconcile", () => {
    const report = makeSellerWeeklyReview();
    report.additionalSales.serviceRevenue! += 1;
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
  });

  it("does not treat a negative component as a positive parts-of-whole chart", () => {
    const report = makeSellerWeeklyReview();
    report.additionalSales.accessoryRevenue = -5;
    report.additionalSales.serviceRevenue = 25;
    report.additionalSales.accessoryMixShare = -25;
    report.additionalSales.serviceMixShare = 125;
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
    report.additionalSales.compositionChartSafe = false;
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(true);
  });

  it("requires a dedicated seller prompt for both ready and pending enrichment", () => {
    const report = makeSellerWeeklyReview();
    report.aiEnhancement = { state: "PREPARING", promptVersion: "weekly-interpretation-v25",
      contentSchemaVersion: 4, publishedAt: null };
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
    report.aiEnhancement.promptVersion = "weekly-interpretation-v26";
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(true);
    report.aiEnhancement.state = "READY";
    expect(sellerWeeklyReviewSchema.safeParse(report).success).toBe(false);
  });

  it("never substitutes a legacy report for a preparing seller response", () => {
    expect(sellerWeeklyReviewViewSchema.safeParse({ freshness: "PREPARING", report: null }).success).toBe(true);
    expect(sellerWeeklyReviewViewSchema.safeParse({ freshness: "CURRENT", report: null }).success).toBe(false);
    expect(sellerWeeklyReviewViewSchema.safeParse({ freshness: "PREPARING", report: makeSellerWeeklyReview() }).success)
      .toBe(false);
  });
});
