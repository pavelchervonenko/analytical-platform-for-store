import { z } from "zod";
import { apiClient } from "../api/client";
import { storeDataStatusSchema } from "../api/contracts";

const healthSchema = z.enum(["OK", "WARNING", "ERROR"]);
const freshnessSchema = z.enum(["NOT_SYNCED", "CURRENT", "STALE", "SYNCING", "ERROR"]);
const severitySchema = z.enum(["INFO", "WARNING", "ERROR"]);

export const qualityActionSchema = z.enum(["NONE", "WAIT_FOR_SYNC", "RUN_SYNC", "REVIEW_SOURCE_DOCUMENT"]);
export const periodQualityActionSchema = z.enum([
  "NONE", "WAIT_FOR_SYNC", "RUN_SYNC", "SET_STORE_PLAN", "UPDATE_WORK_SCHEDULE",
  "REVIEW_EMPLOYEE_ELIGIBILITY", "CLASSIFY_PRODUCTS", "PROVIDE_COST_DATA",
  "CALCULATE_PAYROLL", "RECALCULATE_PAYROLL", "FINALIZE_RATING", "REVIEW_DATA_ISSUES"
]);

const storeSummarySchema = z.object({
  storeId: z.string().uuid(), storeName: z.string(), status: healthSchema,
  freshnessStatus: freshnessSchema, dataThroughDate: z.string().nullable(), lagDays: z.number().int().nullable(),
  openIssueCount: z.number().int().nonnegative(), errorCount: z.number().int().nonnegative(),
  warningCount: z.number().int().nonnegative(), infoCount: z.number().int().nonnegative(), checkedAt: z.string()
});

const overviewSchema = z.object({
  checkedAt: z.string(), storeCount: z.number().int().nonnegative(), okStoreCount: z.number().int().nonnegative(),
  warningStoreCount: z.number().int().nonnegative(), errorStoreCount: z.number().int().nonnegative(),
  openIssueCount: z.number().int().nonnegative(), stores: z.array(storeSummarySchema)
});

const detailSchema = z.object({
  summary: storeSummarySchema,
  dataStatus: storeDataStatusSchema,
  issues: z.array(z.object({
    key: z.string(), source: z.enum(["SYNCHRONIZATION", "SALES", "RETURNS", "DATA"]), code: z.string(),
    severity: severitySchema, entityType: z.string(), message: z.string(), detectedAt: z.string().nullable(),
    recommendedAction: qualityActionSchema
  }))
});

const areaSchema = z.object({
  code: z.enum(["SOURCE_DATA", "STORE_PLAN", "EMPLOYEE_RATING", "PAYROLL"]), status: healthSchema,
  ready: z.boolean(), issueCount: z.number().int().nonnegative(), errorCount: z.number().int().nonnegative(),
  warningCount: z.number().int().nonnegative(), infoCount: z.number().int().nonnegative()
});

const periodSchema = z.object({
  storeId: z.string().uuid(), periodMonth: z.string(), periodStart: z.string(), periodEnd: z.string(), asOfDate: z.string(),
  status: healthSchema, readyForDecisions: z.boolean(), areas: z.array(areaSchema),
  sourceData: z.object({
    freshnessStatus: freshnessSchema, dataThroughDate: z.string().nullable(), completeThroughAsOf: z.boolean(),
    classificationComplete: z.boolean(), costDataComplete: z.boolean(), includedItemCount: z.number().int().nonnegative(),
    unmappedItemCount: z.number().int().nonnegative(), missingCostItemCount: z.number().int().nonnegative(),
    unexpectedZeroCostItemCount: z.number().int().nonnegative(), openQualityIssueCount: z.number().int().nonnegative()
  }),
  storePlan: z.object({ planPresent: z.boolean(), inputDataCompleteThroughAsOf: z.boolean(), classificationComplete: z.boolean(), unmappedItemCount: z.number().int().nonnegative(), openQualityIssueCount: z.number().int().nonnegative(), formulaVersion: z.string() }),
  employeeRating: z.object({ planCoverageComplete: z.boolean(), employeeCount: z.number().int().nonnegative(), eligibleEmployeeCount: z.number().int().nonnegative(), employeeWithShiftCount: z.number().int().nonnegative(), rankedEmployeeCount: z.number().int().nonnegative(), salesWithoutShiftCount: z.number().int().nonnegative(), insufficientScoreCoverageCount: z.number().int().nonnegative(), historyStatus: z.string(), formulaVersion: z.string() }),
  payroll: z.object({ readinessStatus: z.string(), canCalculate: z.boolean(), canApprove: z.boolean(), planPresent: z.boolean(), schemePresent: z.boolean(), salesDayCount: z.number().int().nonnegative(), scheduledDayCount: z.number().int().nonnegative(), unmappedItemCount: z.number().int().nonnegative(), missingCostItemCount: z.number().int().nonnegative(), daysWithoutShift: z.number().int().nonnegative(), calculated: z.boolean(), runStatus: z.string().nullable(), freshness: z.unknown().nullable() }),
  issues: z.array(z.object({ key: z.string(), area: z.string(), code: z.string(), severity: severitySchema, message: z.string(), affectedCount: z.number().int().nullable(), recommendedAction: periodQualityActionSchema })),
  checkedAt: z.string()
});

export type QualityOverview = z.infer<typeof overviewSchema>;
export type StoreQualityDetail = z.infer<typeof detailSchema>;
export type StorePeriodQuality = z.infer<typeof periodSchema>;
export type QualityAction = z.infer<typeof qualityActionSchema> | z.infer<typeof periodQualityActionSchema>;

export const qualityKeys = {
  overview: ["data-quality", "summary"] as const,
  store: (storeId: string) => ["stores", storeId, "data-quality"] as const,
  period: (storeId: string, month: string, asOf: string) => ["stores", storeId, "period-quality-detail", month, asOf] as const
};

export function getQualityOverview(): Promise<QualityOverview> {
  return apiClient.request("/api/data-quality/summary", { schema: overviewSchema });
}

export function getStoreQuality(storeId: string): Promise<StoreQualityDetail> {
  return apiClient.request(`/api/stores/${encodeURIComponent(storeId)}/data-quality`, { schema: detailSchema });
}

export function getStorePeriodQuality(storeId: string, month: string, asOf: string): Promise<StorePeriodQuality> {
  const query = new URLSearchParams({ asOf }).toString();
  return apiClient.request(`/api/stores/${encodeURIComponent(storeId)}/period-quality/${encodeURIComponent(month)}?${query}`, { schema: periodSchema });
}
