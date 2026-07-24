import { z } from "zod";
import { apiClient } from "../api/client";

const roleSchema = z.enum(["ADMIN", "MANAGER"]);
const adminUserSchema = z.object({
  id: z.string().uuid(), email: z.string().email(), displayName: z.string(), role: roleSchema, active: z.boolean(),
  passwordChangeRequired: z.boolean(), allStores: z.boolean(), storeIds: z.array(z.string().uuid()),
  lastLoginAt: z.string().nullable(), version: z.number().int().nonnegative()
});
const adminUsersSchema = z.array(adminUserSchema);

const syncJobSchema = z.object({
  id: z.string().uuid(), connectionId: z.string().uuid(), requestedById: z.string().uuid().nullable(), jobType: z.string(),
  status: z.enum(["PENDING", "RUNNING", "WAITING_RETRY", "SUCCESS", "FAILED", "CANCELLED"]), phase: z.string().nullable(),
  periodStart: z.string(), periodEnd: z.string(), cursorStart: z.string().nullable(), currentWindowEnd: z.string().nullable(),
  windowSizeMinutes: z.number().int(), attemptCount: z.number().int(), maxAttempts: z.number().int(), completedSteps: z.number().int(),
  totalRetries: z.number().int(), cancelRequested: z.boolean(), nextAttemptAt: z.string().nullable(), leaseUntil: z.string().nullable(),
  errorSummary: z.string().nullable(), startedAt: z.string().nullable(), finishedAt: z.string().nullable(),
  createdAt: z.string(), updatedAt: z.string()
});

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

const categorySchema = z.enum(["TECH_TIER_1", "TECH_TIER_2", "ACCESSORY", "SERVICE", "PLAYSTATION_SUBSCRIPTION", "PAID_REPAIR", "EXCLUDE"]);
const assignmentSchema = z.object({ id: z.string().uuid(), productId: z.string().uuid(), productName: z.string(), categoryCode: categorySchema, validFrom: z.string(), validTo: z.string().nullable(), assignedBy: z.string().uuid(), changeReason: z.string(), version: z.number().int(), createdAt: z.string() });

export type AdminUser = z.infer<typeof adminUserSchema>;
export type SyncJob = z.infer<typeof syncJobSchema>;
export type RatingScheme = z.infer<typeof ratingSchemeSchema>;
export type PayrollScheme = z.infer<typeof payrollSchemeSchema>;
export type PayrollCategory = z.infer<typeof categorySchema>;

export interface CreateAdminUserInput { email: string; temporaryPassword: string; displayName: string; role: "ADMIN" | "MANAGER"; storeIds: string[]; }
export interface UpdateAdminUserInput { displayName: string; role: "ADMIN" | "MANAGER"; active: boolean; }
export interface RatingSchemeInput { code: string; effectiveFrom: string; contributionWeight: number; efficiencyWeight: number; structureWeight: number; attachWeight: number; accessoryStructureWeight: number; serviceStructureWeight: number; minimumAttachDenominator: number; scoreCap: number; minimumCoveragePercent: number; }
export interface PayrollSchemeInput { code: string; effectiveFrom: string; achievedPercentage: number; missedPercentage: number; achievedTier1Rate: number; missedTier1Rate: number; achievedTier2Rate: number; missedTier2Rate: number; advanceAmount: number; }

export const adminKeys = {
  users: ["admin", "users"] as const, syncJobs: ["admin", "sync-jobs"] as const,
  ratingSchemes: ["admin", "rating-schemes"] as const, payrollSchemes: ["admin", "payroll-schemes"] as const
};

export const getAdminUsers = (): Promise<AdminUser[]> => apiClient.request("/api/admin/users", { schema: adminUsersSchema });
export const createAdminUser = (input: CreateAdminUserInput): Promise<AdminUser> => apiClient.request("/api/admin/users", { method: "POST", body: input, schema: adminUserSchema });
export const updateAdminUser = (id: string, input: UpdateAdminUserInput): Promise<AdminUser> => apiClient.request(`/api/admin/users/${encodeURIComponent(id)}`, { method: "PUT", body: input, schema: adminUserSchema });
export const replaceUserStoreAccess = (id: string, storeIds: string[]): Promise<AdminUser> => apiClient.request(`/api/admin/users/${encodeURIComponent(id)}/store-access`, { method: "PUT", body: { storeIds }, schema: adminUserSchema });
export const resetAdminUserPassword = (id: string, temporaryPassword: string): Promise<AdminUser> => apiClient.request(`/api/admin/users/${encodeURIComponent(id)}/reset-password`, { method: "POST", body: { temporaryPassword }, schema: adminUserSchema });

export const getSyncJobs = (): Promise<SyncJob[]> => apiClient.request("/api/sync/jobs?limit=50", { schema: z.array(syncJobSchema) });
export const createBackfill = (periodStart: string, periodEndInclusive: string): Promise<SyncJob> => apiClient.request("/api/sync/jobs/backfill", { method: "POST", body: { periodStart, periodEndInclusive }, schema: syncJobSchema });
export const cancelSyncJob = (id: string): Promise<SyncJob> => apiClient.request(`/api/sync/jobs/${encodeURIComponent(id)}/cancel`, { method: "POST", schema: syncJobSchema });

export const getRatingSchemes = (): Promise<RatingScheme[]> => apiClient.request("/api/admin/rating-schemes", { schema: z.array(ratingSchemeSchema) });
export const createRatingScheme = (input: RatingSchemeInput): Promise<RatingScheme> => apiClient.request("/api/admin/rating-schemes", { method: "POST", body: input, schema: ratingSchemeSchema });
export const getPayrollSchemes = (): Promise<PayrollScheme[]> => apiClient.request("/api/admin/payroll-schemes", { schema: z.array(payrollSchemeSchema) });
export const createPayrollScheme = (input: PayrollSchemeInput): Promise<PayrollScheme> => apiClient.request("/api/admin/payroll-schemes", { method: "POST", body: input, schema: payrollSchemeSchema });

export function classifyProducts(validFrom: string, reason: string, assignments: { productId: string; categoryCode: PayrollCategory }[]) {
  return apiClient.request("/api/admin/payroll-category-assignments/bulk", { method: "POST", body: { validFrom, reason, assignments }, schema: z.array(assignmentSchema) });
}
