import { z } from "zod";
import {
  actionSchema,
  evidenceSchema,
  limitationSchema,
  validateWeeklyReview,
  weeklyReviewBaseSchema,
  weeklyReviewMetricSchema
} from "./weeklyReviewContract";

const hash = z.string().regex(/^[a-f0-9]{64}$/u);
const shape = weeklyReviewBaseSchema.shape;
const sellerAction = z.object({
  ...actionSchema.shape,
  actionType: z.literal("REVIEW_SELLER_METRIC"),
  scope: z.enum(["TEAM", "EMPLOYEE"])
}).superRefine((action, context) => {
  if ((action.scope === "EMPLOYEE") !== (action.employeePublicId !== null)) {
    context.addIssue({ code: "custom", message: "Employee action scope must match employeePublicId", path: ["employeePublicId"] });
  }
});
const sellerCard = shape.employees.element.extend({
  sortGroup: z.literal("FINANCIAL_ONLY"),
  action: sellerAction.nullable()
});

export const sellerWeeklyReviewSchema = z.object({
  ...shape,
  contractVersion: z.literal(3),
  scope: z.literal("SELLERS"),
  membership: z.object({
    basis: z.enum(["CURRENT_RANKING_AT_GENERATION", "HISTORICAL_DOCUMENT_MEMBERSHIP_V1"]),
    currentCohortHash: hash,
    previousCohortHash: hash,
    actionabilityRosterHash: hash,
    actionabilityAsOf: z.iso.datetime({ offset: true }),
    selectedSellerCount: z.number().int().nonnegative()
  }),
  sourceIdentityHash: hash,
  sourceCoverage: z.array(shape.sourceCoverage.element.extend({
    sourceCode: z.enum(["SALES", "RETURNS", "ORDERS", "CLASSIFICATION", "COST", "EMPLOYEE_ATTRIBUTION", "SHIFTS"])
  })),
  additionalSales: z.object({
    revenue: weeklyReviewMetricSchema,
    shareOfSellerRevenue: weeklyReviewMetricSchema,
    accessoryRevenue: z.number().nullable(),
    serviceRevenue: z.number().nullable(),
    accessoryMixShare: z.number().nullable(),
    serviceMixShare: z.number().nullable(),
    integrityResidual: z.literal(0).nullable(),
    compositionChartSafe: z.boolean()
  }),
  factors: z.array(shape.factors.element.extend({ kind: z.literal("SELLER_RESULT_CHANGE") })).max(3),
  actions: z.array(sellerAction).max(3),
  evidence: z.array(evidenceSchema.extend({ scope: z.enum(["SELLERS", "TEAM", "EMPLOYEE"]) })),
  limitations: z.array(limitationSchema.extend({ scope: z.enum(["SELLERS", "BLOCK", "TEAM", "EMPLOYEE", "METRIC"]) })),
  employees: z.array(z.object({ card: sellerCard, actionableNow: z.boolean() })).max(100),
  teamDisplay: z.object({
    totalCount: z.number().int().nonnegative(),
    displayedCount: z.number().int().nonnegative().max(100),
    hiddenCurrentNetRevenue: z.number(),
    hiddenPreviousNetRevenue: z.number(),
    hiddenCurrentAdditionalRevenue: z.number(),
    hiddenPreviousAdditionalRevenue: z.number()
  }),
  // Optional legacy STORE enrichment cannot be attached to the new seller contract.
  aiEnhancement: shape.aiEnhancement.extend({
    state: z.enum(["DISABLED", "NOT_APPLICABLE", "PREPARING", "DELAYED", "UNAVAILABLE", "READY"])
  })
}).superRefine((review, context) => {
  const issue = (message: string, path: PropertyKey[]) => context.addIssue({ code: "custom", message, path });
  validateWeeklyReview({ ...review, employees: review.employees.map((item) => item.card) }, context,
    [review.additionalSales.revenue, review.additionalSales.shareOfSellerRevenue]);
  if (["READY", "PREPARING", "DELAYED", "UNAVAILABLE"].includes(review.aiEnhancement.state)
      && (review.aiEnhancement.promptVersion !== "weekly-interpretation-v26"
        || review.aiEnhancement.contentSchemaVersion !== 4
        || (review.aiEnhancement.state === "READY" && review.aiEnhancement.publishedAt === null))) {
    issue("Seller enrichment must use its dedicated versioned prompt", ["aiEnhancement"]);
  }
  if (review.membership.currentCohortHash !== review.membership.previousCohortHash
      || (review.membership.basis === "CURRENT_RANKING_AT_GENERATION"
        && review.membership.currentCohortHash !== review.membership.actionabilityRosterHash)) {
    issue("Both weeks require one selection; current-ranking actions must use the same cohort", ["membership"]);
  }
  const display = review.teamDisplay;
  if (display.displayedCount !== review.employees.length || display.displayedCount > display.totalCount
      || (review.reportState !== "BLOCKED" && display.totalCount !== review.membership.selectedSellerCount)) {
    issue("Seller card counts must reconcile without truncating totals", ["teamDisplay"]);
  }
  review.employees.forEach((item, index) => {
    if (!item.actionableNow && item.card.action !== null) {
      issue("Non-actionable seller cannot receive a future action", ["employees", index, "card", "action"]);
    }
  });
  const checkIdentifiers = (value: unknown, field = "") => {
    if (Array.isArray(value)) value.forEach((item) => checkIdentifiers(item, field));
    else if (value !== null && typeof value === "object") {
      Object.entries(value).forEach(([key, item]) => checkIdentifiers(item, key));
    } else if (typeof value === "string" && /(?:Id|Code|Ref|Refs|scope|code)$/u.test(field)
        && /^(?:STORE$|STORE[.:_])/iu.test(value)) {
      issue("Seller report contains legacy STORE identifiers", [field]);
    }
  };
  checkIdentifiers(review);
  const additional = review.additionalSales;
  if (additional.revenue.code !== "ADDITIONAL_REVENUE" || additional.revenue.unit !== "RUB"
      || additional.shareOfSellerRevenue.code !== "ADDITIONAL_SHARE"
      || additional.shareOfSellerRevenue.unit !== "PERCENT") {
    issue("Additional sales require separate monetary and percentage metrics", ["additionalSales"]);
  }
  for (const metric of [additional.revenue, additional.shareOfSellerRevenue]) {
    metric.evidenceRefs.forEach((reference) => {
      if (review.evidence.filter((item) => item.evidenceRef === reference).length !== 1) {
        issue("Additional sales evidence must resolve uniquely", ["additionalSales"]);
      }
    });
  }
  if (additional.accessoryRevenue !== null && additional.serviceRevenue !== null
      && additional.revenue.current !== null
      && Math.round(additional.accessoryRevenue * 100) + Math.round(additional.serviceRevenue * 100)
        !== Math.round(additional.revenue.current * 100)) {
    issue("Additional sales components must reconcile", ["additionalSales"]);
  }
  if (additional.compositionChartSafe && (additional.accessoryRevenue === null
      || additional.serviceRevenue === null || additional.accessoryRevenue < 0 || additional.serviceRevenue < 0
      || additional.accessoryRevenue + additional.serviceRevenue <= 0)) {
    issue("Negative or empty composition cannot be shown as a parts-of-whole chart", ["additionalSales"]);
  }
});

export const sellerWeeklyReviewViewSchema = z.object({
  freshness: z.enum(["PREPARING", "CURRENT", "STALE"]),
  report: sellerWeeklyReviewSchema.nullable()
}).superRefine((view, context) => {
  if ((view.freshness === "PREPARING") !== (view.report === null)) {
    context.addIssue({ code: "custom", message: "Only preparing seller reports may omit content", path: ["report"] });
  }
});

export type SellerWeeklyReview = z.infer<typeof sellerWeeklyReviewSchema>;
export type SellerWeeklyReviewView = z.infer<typeof sellerWeeklyReviewViewSchema>;
