import {
  attachRateSchema,
  averageKpiSchema,
  categoryKpiSchema,
  currentUserSchema,
  employeeCardSchema,
  employeeDirectorySchema,
  employeeRatingResultSchema,
  employeeRatingSettingSchema,
  employeeRatingSettingsSchema,
  employeeShiftListSchema,
  performancePlanSchema,
  periodQualitySchema,
  planProgressSchema,
  payrollPreviewSchema,
  payrollReadinessSchema,
  payrollRevisionComparisonSchema,
  payrollRunDetailSchema,
  payrollRunListSchema,
  type PayrollAdjustmentInput,
  reportDetailSchema,
  reportSummaryListSchema,
  storeDataStatusSchema,
  storeKpiSchema,
  storeListSchema,
  type AttachRate,
  type AverageKpi,
  type CategoryKpi,
  type CurrentUser,
  type EmployeeCard,
  type EmployeeDirectory,
  type EmployeeRatingResult,
  type EmployeeRatingSetting,
  type EmployeeShift,
  type PerformancePlan,
  type PerformancePlanInput,
  type WorkShiftInput,
  type PeriodQuality,
  type PayrollPreview,
  type PayrollReadiness,
  type PayrollRevisionComparison,
  type PayrollRunDetail,
  type PayrollRunSummary,
  type PayrollVoidAdjustmentInput,
  type PlanProgress,
  type ReportDetail,
  type ReportSummary,
  type ReportType,
  type StoreDataStatus,
  type StoreKpi,
  type StoreSummary
} from "./contracts";
import { apiClient, isApiClientError } from "./client";

export const queryKeys = {
  session: ["session"] as const,
  stores: ["stores"] as const,
  storeStatus: (storeId: string) => ["stores", storeId, "data-status"] as const,
  storeKpi: (storeId: string, start: string, end: string) => ["stores", storeId, "kpi", start, end] as const,
  categories: (storeId: string, start: string, end: string) => ["stores", storeId, "categories", start, end] as const,
  averages: (storeId: string, start: string, end: string) => ["stores", storeId, "averages", start, end] as const,
  attachRates: (storeId: string, start: string, end: string) => ["stores", storeId, "attach-rates", start, end] as const,
  planProgress: (storeId: string, month: string, asOf: string) => ["stores", storeId, "plan-progress", month, asOf] as const,
  periodQuality: (storeId: string, month: string, asOf: string) => ["stores", storeId, "period-quality", month, asOf] as const,
  employees: (storeId: string) => ["stores", storeId, "employees"] as const,
  employeeDirectory: (storeId: string, start: string, end: string) => ["stores", storeId, "employees", "directory", start, end] as const,
  employeeCard: (storeId: string, employeeId: string, start: string, end: string) => ["stores", storeId, "employees", "card", employeeId, start, end] as const,
  employeeRating: (storeId: string, start: string, end: string) => ["stores", storeId, "employees", "rating", start, end] as const,
  employeeRatingSettings: (storeId: string) => ["stores", storeId, "employees", "settings"] as const,
  performancePlan: (storeId: string, month: string) => ["stores", storeId, "performance-plan", month] as const,
  workSchedule: (storeId: string, start: string, end: string) => ["stores", storeId, "work-schedule", start, end] as const,
  payrollReadiness: (storeId: string, month: string) => ["stores", storeId, "payroll", month, "readiness"] as const,
  payrollPreview: (storeId: string, month: string) => ["stores", storeId, "payroll", month, "preview"] as const,
  payrollLatest: (storeId: string, month: string) => ["stores", storeId, "payroll", month, "latest"] as const,
  payrollRuns: (storeId: string) => ["stores", storeId, "payroll", "runs"] as const,
  payrollRun: (storeId: string, runId: string) => ["stores", storeId, "payroll", "run", runId] as const,
  payrollComparison: (storeId: string, previousId: string, currentId: string) => ["stores", storeId, "payroll", "compare", previousId, currentId] as const,
  reports: (storeId: string, year?: number, type?: ReportType) => ["stores", storeId, "reports", year, type] as const,
  report: (storeId: string, reportId: string) => ["stores", storeId, "reports", reportId] as const
};

function periodQuery(start: string, end: string): string {
  return new URLSearchParams({ periodStart: start, periodEnd: end }).toString();
}

function storePath(storeId: string): string {
  return `/api/stores/${encodeURIComponent(storeId)}`;
}

export function getCurrentUser(): Promise<CurrentUser> {
  return apiClient.request("/api/auth/me", {
    schema: currentUserSchema,
    notifyOnUnauthorized: false
  });
}

export function getStores(): Promise<StoreSummary[]> {
  return apiClient.request("/api/stores", { schema: storeListSchema });
}

export function getStoreStatus(storeId: string): Promise<StoreDataStatus> {
  return apiClient.request(`${storePath(storeId)}/data-status`, { schema: storeDataStatusSchema });
}

export function getStoreKpi(storeId: string, start: string, end: string): Promise<StoreKpi> {
  return apiClient.request(`${storePath(storeId)}/kpi?${periodQuery(start, end)}`, { schema: storeKpiSchema });
}

export function getCategoryKpi(storeId: string, start: string, end: string): Promise<CategoryKpi> {
  return apiClient.request(`${storePath(storeId)}/kpi/categories?${periodQuery(start, end)}`, { schema: categoryKpiSchema });
}

export function getAverageKpi(storeId: string, start: string, end: string): Promise<AverageKpi> {
  return apiClient.request(`${storePath(storeId)}/kpi/averages?${periodQuery(start, end)}`, { schema: averageKpiSchema });
}

export function getAttachRates(storeId: string, start: string, end: string): Promise<AttachRate> {
  return apiClient.request(`${storePath(storeId)}/kpi/attach-rates?${periodQuery(start, end)}`, { schema: attachRateSchema });
}

export async function getPlanProgress(storeId: string, month: string, asOf: string): Promise<PlanProgress | null> {
  const query = new URLSearchParams({ asOf }).toString();
  try {
    return await apiClient.request(`${storePath(storeId)}/performance-plans/${encodeURIComponent(month)}/progress?${query}`, {
      schema: planProgressSchema
    });
  } catch (error) {
    if (isApiClientError(error) && error.status === 404 && error.code === "PERFORMANCE_PLAN_NOT_FOUND") {
      return null;
    }
    throw error;
  }
}

export function getPeriodQuality(storeId: string, month: string, asOf: string): Promise<PeriodQuality> {
  const query = new URLSearchParams({ asOf }).toString();
  return apiClient.request(`${storePath(storeId)}/period-quality/${encodeURIComponent(month)}?${query}`, {
    schema: periodQualitySchema
  });
}

export function getEmployeeDirectory(storeId: string, start: string, end: string): Promise<EmployeeDirectory> {
  return apiClient.request(`${storePath(storeId)}/employees?${periodQuery(start, end)}`, {
    schema: employeeDirectorySchema
  });
}

export function getEmployeeCard(storeId: string, employeeId: string, start: string, end: string): Promise<EmployeeCard> {
  return apiClient.request(`${storePath(storeId)}/employees/${encodeURIComponent(employeeId)}?${periodQuery(start, end)}`, {
    schema: employeeCardSchema
  });
}

export function getEmployeeRating(storeId: string, start: string, end: string): Promise<EmployeeRatingResult> {
  return apiClient.request(`${storePath(storeId)}/employee-ratings?${periodQuery(start, end)}`, {
    schema: employeeRatingResultSchema
  });
}

export function getEmployeeRatingSettings(storeId: string): Promise<EmployeeRatingSetting[]> {
  return apiClient.request(`${storePath(storeId)}/employee-rating-settings`, {
    schema: employeeRatingSettingsSchema
  });
}

export function updateEmployeeRatingSetting(
  storeId: string,
  employeeId: string,
  participatesInRanking: boolean,
  version: number
): Promise<EmployeeRatingSetting> {
  return apiClient.request(`${storePath(storeId)}/employee-rating-settings/${encodeURIComponent(employeeId)}`, {
    method: "PUT",
    body: { participatesInRanking, version },
    schema: employeeRatingSettingSchema
  });
}

export function finalizeEmployeeRating(
  storeId: string,
  start: string,
  end: string
): Promise<EmployeeRatingResult> {
  return apiClient.request(`${storePath(storeId)}/employee-ratings/finalize?${periodQuery(start, end)}`, {
    method: "POST",
    schema: employeeRatingResultSchema
  });
}

export async function getPerformancePlan(storeId: string, month: string): Promise<PerformancePlan | null> {
  try {
    return await apiClient.request(`${storePath(storeId)}/performance-plans/${encodeURIComponent(month)}`, {
      schema: performancePlanSchema
    });
  } catch (error) {
    if (isApiClientError(error) && error.status === 404 && error.code === "PERFORMANCE_PLAN_NOT_FOUND") return null;
    throw error;
  }
}

export function upsertPerformancePlan(storeId: string, month: string, input: PerformancePlanInput): Promise<PerformancePlan> {
  return apiClient.request(`${storePath(storeId)}/performance-plans/${encodeURIComponent(month)}`, {
    method: "PUT",
    body: input,
    schema: performancePlanSchema
  });
}

export function getWorkSchedule(storeId: string, start: string, end: string): Promise<EmployeeShift[]> {
  return apiClient.request(`${storePath(storeId)}/work-schedule?${periodQuery(start, end)}`, {
    schema: employeeShiftListSchema
  });
}

export function replaceWorkScheduleDay(storeId: string, workDate: string, shifts: WorkShiftInput[]): Promise<EmployeeShift[]> {
  return apiClient.request(`${storePath(storeId)}/work-schedule/${encodeURIComponent(workDate)}`, {
    method: "PUT",
    body: { shifts },
    schema: employeeShiftListSchema
  });
}

export function getPayrollReadiness(storeId: string, month: string): Promise<PayrollReadiness> {
  return apiClient.request(`${storePath(storeId)}/payroll/${encodeURIComponent(month)}/readiness`, { schema: payrollReadinessSchema });
}

export function getPayrollPreview(storeId: string, month: string): Promise<PayrollPreview> {
  return apiClient.request(`${storePath(storeId)}/payroll/${encodeURIComponent(month)}/preview`, { schema: payrollPreviewSchema });
}

export async function getLatestPayroll(storeId: string, month: string): Promise<PayrollRunDetail | null> {
  try {
    return await apiClient.request(`${storePath(storeId)}/payroll/${encodeURIComponent(month)}`, { schema: payrollRunDetailSchema });
  } catch (error) {
    if (isApiClientError(error) && error.status === 404 && error.code === "PAYROLL_NOT_FOUND") return null;
    throw error;
  }
}

export function calculatePayroll(storeId: string, month: string, revisionReason?: string): Promise<PayrollRunDetail> {
  return apiClient.request(`${storePath(storeId)}/payroll/${encodeURIComponent(month)}/calculate`, {
    method: "POST", body: revisionReason ? { revisionReason } : undefined, schema: payrollRunDetailSchema
  });
}

export function getPayrollRuns(storeId: string): Promise<PayrollRunSummary[]> {
  return apiClient.request(`${storePath(storeId)}/payroll-runs`, { schema: payrollRunListSchema });
}

export function getPayrollRun(storeId: string, runId: string): Promise<PayrollRunDetail> {
  return apiClient.request(`${storePath(storeId)}/payroll-runs/${encodeURIComponent(runId)}`, { schema: payrollRunDetailSchema });
}

export function addPayrollAdjustment(storeId: string, runId: string, input: PayrollAdjustmentInput): Promise<PayrollRunDetail> {
  return apiClient.request(`${storePath(storeId)}/payroll-runs/${encodeURIComponent(runId)}/adjustments`, {
    method: "POST", body: input, schema: payrollRunDetailSchema
  });
}

export function voidPayrollAdjustment(storeId: string, runId: string, adjustmentId: string, input: PayrollVoidAdjustmentInput): Promise<PayrollRunDetail> {
  return apiClient.request(`${storePath(storeId)}/payroll-runs/${encodeURIComponent(runId)}/adjustments/${encodeURIComponent(adjustmentId)}/void`, {
    method: "POST", body: input, schema: payrollRunDetailSchema
  });
}

export function approvePayroll(storeId: string, runId: string, version: number): Promise<PayrollRunDetail> {
  return apiClient.request(`${storePath(storeId)}/payroll-runs/${encodeURIComponent(runId)}/approve`, {
    method: "POST", body: { version }, schema: payrollRunDetailSchema
  });
}

export function markPayrollPaid(storeId: string, runId: string, version: number): Promise<PayrollRunDetail> {
  return apiClient.request(`${storePath(storeId)}/payroll-runs/${encodeURIComponent(runId)}/paid`, {
    method: "POST", body: { version }, schema: payrollRunDetailSchema
  });
}

export function comparePayrollRevisions(storeId: string, previousRunId: string, currentRunId: string): Promise<PayrollRevisionComparison> {
  return apiClient.request(`${storePath(storeId)}/payroll-runs/${encodeURIComponent(previousRunId)}/compare/${encodeURIComponent(currentRunId)}`, {
    schema: payrollRevisionComparisonSchema
  });
}
export function getReports(
  storeId: string,
  year?: number,
  type?: ReportType
): Promise<ReportSummary[]> {
  const parameters = new URLSearchParams();
  if (year != null) parameters.set("year", String(year));
  if (type) parameters.set("type", type);
  const query = parameters.size > 0 ? `?${parameters.toString()}` : "";
  return apiClient.request(`${storePath(storeId)}/reports${query}`, {
    schema: reportSummaryListSchema
  });
}

export function getReport(storeId: string, reportId: string): Promise<ReportDetail> {
  return apiClient.request(
    `${storePath(storeId)}/reports/${encodeURIComponent(reportId)}`,
    { schema: reportDetailSchema }
  );
}
