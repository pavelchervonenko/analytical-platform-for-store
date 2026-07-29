import { z } from "zod";
import { apiClient } from "../api/client";
import { pageResponseSchema, type PageResponse } from "../api/contracts";
import { forwardCompatibleEnum } from "../api/enumSchema";

const roleSchema = forwardCompatibleEnum(["ADMIN", "MANAGER"]);
const adminUserSchema = z.object({
  id: z.string().uuid(), email: z.string().email(), displayName: z.string(), role: roleSchema, active: z.boolean(),
  passwordChangeRequired: z.boolean(), allStores: z.boolean(), storeIds: z.array(z.string().uuid()),
  lastLoginAt: z.string().nullable(), version: z.number().int().nonnegative()
});
const adminUsersSchema = pageResponseSchema(adminUserSchema);

const syncJobSchema = z.object({
  id: z.string().uuid(), connectionId: z.string().uuid(), requestedById: z.string().uuid().nullable(), jobType: z.string(),
  status: forwardCompatibleEnum(["PENDING", "RUNNING", "WAITING_RETRY", "SUCCESS", "FAILED", "CANCELLED"]), phase: z.string().nullable(),
  periodStart: z.string(), periodEnd: z.string(), cursorStart: z.string().nullable(), currentWindowEnd: z.string().nullable(),
  windowSizeMinutes: z.number().int(), attemptCount: z.number().int(), maxAttempts: z.number().int(), completedSteps: z.number().int(),
  totalRetries: z.number().int(), cancelRequested: z.boolean(), nextAttemptAt: z.string().nullable(), leaseUntil: z.string().nullable(),
  errorSummary: z.string().nullable(), startedAt: z.string().nullable(), finishedAt: z.string().nullable(),
  createdAt: z.string(), updatedAt: z.string()
});

const productCategoryImportResultSchema = z.object({
  requested: z.number().int().positive(),
  productsCreated: z.number().int().nonnegative(),
  assignmentsCreated: z.number().int().nonnegative(),
  assignmentsUnchanged: z.number().int().nonnegative()
});

export interface ProductCategoryImportItem {
  externalProductId: string;
  productName: string;
  categoryCode: string;
  conditionType: "NEW" | "ASIS" | "USED" | "NOT_APPLICABLE" | "UNKNOWN";
}

const ratingSchemeSchema = z.object({
  id: z.string().uuid(), code: z.string(), effectiveFrom: z.string(), contributionWeight: z.number(), efficiencyWeight: z.number(),
  structureWeight: z.number(), attachWeight: z.number(), accessoryStructureWeight: z.number(), serviceStructureWeight: z.number(),
  minimumAttachDenominator: z.number(), scoreCap: z.number(), minimumCoveragePercent: z.number(),
  createdBy: z.string().uuid().nullable(), createdAt: z.string()
});
const payrollSchemeSchema = z.object({
  id: z.string().uuid(), code: z.string(), effectiveFrom: z.string(), achievedPercentage: z.number(), missedPercentage: z.number(),
  achievedTier1Rate: z.number(), missedTier1Rate: z.number(), achievedTier2Rate: z.number(), missedTier2Rate: z.number(), advanceAmount: z.number()
});

const categoryValues = ["TECH_TIER_1", "TECH_TIER_2", "ACCESSORY", "SERVICE", "PLAYSTATION_SUBSCRIPTION", "PAID_REPAIR", "EXCLUDE"] as const;
const categoryResponseSchema = forwardCompatibleEnum(categoryValues);
const assignmentSchema = z.object({ id: z.string().uuid(), productId: z.string().uuid(), productName: z.string(), categoryCode: categoryResponseSchema, validFrom: z.string(), validTo: z.string().nullable(), assignedBy: z.string().uuid(), changeReason: z.string(), version: z.number().int(), createdAt: z.string() });
const reportBackfillJobSchema = z.object({
  id: z.string().uuid(), storeId: z.string().uuid(), requestedById: z.string().uuid().nullable(), year: z.number().int(),
  status: forwardCompatibleEnum(["PENDING", "RUNNING", "WAITING_RETRY", "SUCCESS", "FAILED", "CANCELLED"]),
  phase: forwardCompatibleEnum(["MONTHLY", "ANNUAL"]), cursorMonth: z.number().int().min(1).max(12),
  paidMonthCount: z.number().int().nonnegative(), monthlyCreatedCount: z.number().int().nonnegative(),
  monthlyExistingCount: z.number().int().nonnegative(), annualReportId: z.string().uuid().nullable(),
  attemptCount: z.number().int().nonnegative(), maxAttempts: z.number().int().positive(),
  completedSteps: z.number().int().nonnegative(), totalRetries: z.number().int().nonnegative(),
  cancelRequested: z.boolean(), nextAttemptAt: z.string(), leaseUntil: z.string().nullable(),
  errorSummary: z.string().nullable(), startedAt: z.string().nullable(), finishedAt: z.string().nullable(),
  createdAt: z.string(), updatedAt: z.string()
});

export type AdminUser = z.infer<typeof adminUserSchema>;
export type SyncJob = z.infer<typeof syncJobSchema>;
export type ProductCategoryImportResult = z.infer<typeof productCategoryImportResultSchema>;
export type RatingScheme = z.infer<typeof ratingSchemeSchema>;
export type PayrollScheme = z.infer<typeof payrollSchemeSchema>;
export type PayrollCategory = typeof categoryValues[number];
export type ReportBackfillJob = z.infer<typeof reportBackfillJobSchema>;

export interface CreateAdminUserInput { email: string; temporaryPassword: string; displayName: string; role: "ADMIN" | "MANAGER"; storeIds: string[]; }
export interface UpdateAdminUserInput { displayName: string; role: "ADMIN" | "MANAGER"; active: boolean; }
export interface RatingSchemeInput { code: string; effectiveFrom: string; contributionWeight: number; efficiencyWeight: number; structureWeight: number; attachWeight: number; accessoryStructureWeight: number; serviceStructureWeight: number; minimumAttachDenominator: number; scoreCap: number; minimumCoveragePercent: number; }
export interface PayrollSchemeInput { code: string; effectiveFrom: string; achievedPercentage: number; missedPercentage: number; achievedTier1Rate: number; missedTier1Rate: number; achievedTier2Rate: number; missedTier2Rate: number; advanceAmount: number; }

export const adminKeys = {
  users: ["admin", "users"] as const, syncJobs: ["admin", "sync-jobs"] as const,
  ratingSchemes: ["admin", "rating-schemes"] as const, payrollSchemes: ["admin", "payroll-schemes"] as const,
  reportBackfillJobs: ["admin", "report-backfill-jobs"] as const,
};

export const getAdminUsers = (page = 0, size = 20): Promise<PageResponse<AdminUser>> => apiClient.request(
  `/api/admin/users?${new URLSearchParams({ page: String(page), size: String(size) }).toString()}`,
  { schema: adminUsersSchema }
);
export const createAdminUser = (input: CreateAdminUserInput): Promise<AdminUser> => apiClient.request("/api/admin/users", { method: "POST", body: input, schema: adminUserSchema });
export const updateAdminUser = (id: string, input: UpdateAdminUserInput): Promise<AdminUser> => apiClient.request(`/api/admin/users/${encodeURIComponent(id)}`, { method: "PUT", body: input, schema: adminUserSchema });
export const replaceUserStoreAccess = (id: string, storeIds: string[]): Promise<AdminUser> => apiClient.request(`/api/admin/users/${encodeURIComponent(id)}/store-access`, { method: "PUT", body: { storeIds }, schema: adminUserSchema });
export const resetAdminUserPassword = (id: string, temporaryPassword: string): Promise<AdminUser> => apiClient.request(`/api/admin/users/${encodeURIComponent(id)}/reset-password`, { method: "POST", body: { temporaryPassword }, schema: adminUserSchema });

export const getSyncJobs = (): Promise<SyncJob[]> => apiClient.request("/api/sync/jobs?limit=50", { schema: z.array(syncJobSchema) });
export const getSyncJob = (id: string): Promise<SyncJob> => apiClient.request(`/api/sync/jobs/${encodeURIComponent(id)}`, { schema: syncJobSchema });
export const createBackfill = (periodStart: string, periodEndInclusive: string): Promise<SyncJob> => apiClient.request("/api/sync/jobs/backfill", { method: "POST", body: { periodStart, periodEndInclusive }, schema: syncJobSchema });
export const cancelSyncJob = (id: string): Promise<SyncJob> => apiClient.request(`/api/sync/jobs/${encodeURIComponent(id)}/cancel`, { method: "POST", schema: syncJobSchema });
export const importProductCategories = (
  connectionKey: string,
  input: { validFrom: string; ruleVersion: string; changeReason?: string; assignments: ProductCategoryImportItem[] }
): Promise<ProductCategoryImportResult> => apiClient.request(
  `/api/integration-connections/${encodeURIComponent(connectionKey)}/product-category-imports`,
  { method: "POST", body: input, schema: productCategoryImportResultSchema, timeoutMs: 120_000 }
);
export const getReportBackfillJobs = (): Promise<ReportBackfillJob[]> => apiClient.request(
  "/api/admin/reports/backfill?limit=50", { schema: z.array(reportBackfillJobSchema) }
);
export const backfillReports = (storeId: string, year: number): Promise<ReportBackfillJob> => apiClient.request(
  `/api/admin/reports/backfill?${new URLSearchParams({ storeId, year: String(year) }).toString()}`,
  {
    method: "POST",
    idempotencyScope: `reports:backfill:${storeId}:${year}`,
    schema: reportBackfillJobSchema
  }
);
export const cancelReportBackfill = (id: string): Promise<ReportBackfillJob> => apiClient.request(
  `/api/admin/reports/backfill/${encodeURIComponent(id)}/cancel`, { method: "POST", schema: reportBackfillJobSchema }
);

export const getRatingSchemes = (page = 0, size = 20): Promise<PageResponse<RatingScheme>> => apiClient.request(
  `/api/admin/rating-schemes?${new URLSearchParams({ page: String(page), size: String(size) }).toString()}`,
  { schema: pageResponseSchema(ratingSchemeSchema) }
);
export const createRatingScheme = (input: RatingSchemeInput): Promise<RatingScheme> => apiClient.request("/api/admin/rating-schemes", { method: "POST", body: input, schema: ratingSchemeSchema });
export const getPayrollSchemes = (page = 0, size = 20): Promise<PageResponse<PayrollScheme>> => apiClient.request(
  `/api/admin/payroll-schemes?${new URLSearchParams({ page: String(page), size: String(size) }).toString()}`,
  { schema: pageResponseSchema(payrollSchemeSchema) }
);
export const createPayrollScheme = (input: PayrollSchemeInput): Promise<PayrollScheme> => apiClient.request("/api/admin/payroll-schemes", { method: "POST", body: input, schema: payrollSchemeSchema });

export function classifyProducts(validFrom: string, reason: string, assignments: { productId: string; categoryCode: PayrollCategory }[]) {
  return apiClient.request("/api/admin/payroll-category-assignments/bulk", { method: "POST", body: { validFrom, reason, assignments }, schema: z.array(assignmentSchema) });
}
