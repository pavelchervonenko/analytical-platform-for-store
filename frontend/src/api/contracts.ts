import { z } from "zod";
import type {
  ActiveSessionListResponse,
  CsrfConfigurationResponse,
  SystemStatusView
} from "./generated";
import { forwardCompatibleEnum } from "./enumSchema";

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export const pageResponseSchema = <T extends z.ZodType>(itemSchema: T) => z.object({
  items: z.array(itemSchema),
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  totalElements: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
  hasNext: z.boolean(),
  hasPrevious: z.boolean()
});

export const apiErrorPayloadSchema = z.object({
  timestamp: z.string(),
  status: z.number().int(),
  code: z.string(),
  message: z.string(),
  path: z.string(),
  correlationId: z.string().min(1).max(64)
});

export type ApiErrorPayload = z.infer<typeof apiErrorPayloadSchema>;

export const csrfConfigurationSchema = z.object({
  headerName: z.string().min(1),
  cookieName: z.string().min(1)
}) satisfies z.ZodType<CsrfConfigurationResponse>;

export type CsrfConfiguration = z.infer<typeof csrfConfigurationSchema>;

export const systemStatusSchema = z.object({
  application: z.string(),
  version: z.string(),
  apiContractVersion: z.string().regex(/^[1-9][0-9]*$/u),
  time: z.string()
}) satisfies z.ZodType<SystemStatusView>;

export type SystemStatus = z.infer<typeof systemStatusSchema>;

export const currentUserSchema = z.object({
  id: z.string().uuid(),
  email: z.string().email(),
  displayName: z.string(),
  role: forwardCompatibleEnum(["ADMIN", "MANAGER"]),
  passwordChangeRequired: z.boolean(),
  allStores: z.boolean(),
  storeIds: z.array(z.string().uuid())
});

export type CurrentUser = z.infer<typeof currentUserSchema>;

export const activeSessionSchema = z.object({
  sessionReference: z.string().min(1).max(256),
  lastSeenAt: z.string().datetime({ offset: true }),
  current: z.boolean()
});

export const activeSessionListSchema = z.object({
  sessions: z.array(activeSessionSchema).max(3)
}) satisfies z.ZodType<ActiveSessionListResponse>;

export type ActiveSession = z.infer<typeof activeSessionSchema>;

export const storeSummarySchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  address: z.string().nullable(),
  timezone: z.string(),
  businessDayStart: z.string(),
  opensAt: z.string(),
  closesAt: z.string(),
  active: z.boolean()
});

export const storeListSchema = z.array(storeSummarySchema);
export type StoreSummary = z.infer<typeof storeSummarySchema>;

const storeSyncActivitySchema = z.object({
  active: z.boolean(),
  id: z.string().uuid().nullable(),
  type: z.string().nullable(),
  status: z.string().nullable(),
  phase: z.string().nullable(),
  startedAt: z.string().nullable(),
  nextAttemptAt: z.string().nullable()
});

export const storeDataStatusSchema = z.object({
  storeId: z.string().uuid(),
  status: z.string(),
  expectedThroughDate: z.string(),
  dataThroughDate: z.string().nullable(),
  salesDataThroughDate: z.string().nullable(),
  returnsDataThroughDate: z.string().nullable(),
  lagDays: z.number().int().nullable(),
  lastCompletedSyncAt: z.string().nullable(),
  synchronization: storeSyncActivitySchema,
  openQualityIssueCount: z.number().int().nonnegative(),
  lastError: z.string().nullable(),
  lastErrorAt: z.string().nullable(),
  checkedAt: z.string()
});

export type StoreDataStatus = z.infer<typeof storeDataStatusSchema>;

const storeKpiDataQualitySchema = z.object({
  completeCostData: z.boolean(),
  includedItemCount: z.number().int().nonnegative(),
  unmappedItemCount: z.number().int().nonnegative(),
  missingCostItemCount: z.number().int().nonnegative(),
  unexpectedZeroCostItemCount: z.number().int().nonnegative(),
  storeOpenQualityIssueCount: z.number().int().nonnegative()
});

export const storeKpiSchema = z.object({
  storeId: z.string().uuid(),
  periodStart: z.string(),
  periodEnd: z.string(),
  formulaVersion: z.string(),
  netRevenue: z.number(),
  netQuantity: z.number(),
  costAmount: z.number().nullable(),
  grossProfit: z.number().nullable(),
  marginPercent: z.number().nullable(),
  dataQuality: storeKpiDataQualitySchema
});

export type StoreKpi = z.infer<typeof storeKpiSchema>;

const categoryDataQualitySchema = z.object({
  completeCostData: z.boolean(),
  includedItemCount: z.number().int().nonnegative(),
  missingCostItemCount: z.number().int().nonnegative(),
  unexpectedZeroCostItemCount: z.number().int().nonnegative()
});

const categoryMetricsSchema = z.object({
  netRevenue: z.number(),
  netQuantity: z.number(),
  costAmount: z.number().nullable(),
  grossProfit: z.number().nullable(),
  marginPercent: z.number().nullable(),
  dataQuality: categoryDataQualitySchema
});

const categoryGroupSchema = z.object({
  groupCode: z.string(),
  groupName: z.string(),
  metrics: categoryMetricsSchema
});

const categoryEntrySchema = z.object({
  categoryCode: z.string(),
  categoryName: z.string(),
  categoryKind: z.string(),
  deviceFamily: z.string().nullable(),
  categoryActive: z.boolean(),
  countsAsPhone: z.boolean(),
  countsAsDevice: z.boolean(),
  countsAsAdditionalRevenue: z.boolean(),
  metrics: categoryMetricsSchema
});

export const categoryKpiSchema = z.object({
  storeId: z.string().uuid(),
  periodStart: z.string(),
  periodEnd: z.string(),
  formulaVersion: z.string(),
  groups: z.array(categoryGroupSchema),
  categories: z.array(categoryEntrySchema)
});

export type CategoryKpi = z.infer<typeof categoryKpiSchema>;
export type CategoryKpiEntry = z.infer<typeof categoryEntrySchema>;

const averageMetricSnapshotSchema = z.object({
  numerator: z.number(),
  denominator: z.number(),
  value: z.number().nullable()
});

const averageMetricComparisonSchema = z.object({
  current: averageMetricSnapshotSchema,
  previous: averageMetricSnapshotSchema,
  changePercent: z.number().nullable()
});

export const averageKpiSchema = z.object({
  storeId: z.string().uuid(),
  periodStart: z.string(),
  periodEnd: z.string(),
  previousPeriodStart: z.string(),
  previousPeriodEnd: z.string(),
  formulaVersion: z.string(),
  averageReceipt: averageMetricComparisonSchema,
  additionalRevenuePerPhone: averageMetricComparisonSchema,
  categoryAveragePrices: z.array(z.object({
    categoryCode: z.string(),
    categoryName: z.string(),
    categoryActive: z.boolean(),
    averageUnitPrice: averageMetricComparisonSchema
  }))
});

export type AverageKpi = z.infer<typeof averageKpiSchema>;

const attachRateEntrySchema = z.object({
  metricCode: z.string(),
  numeratorCategoryCode: z.string(),
  denominatorCode: z.string(),
  numeratorQuantity: z.number(),
  denominatorQuantity: z.number(),
  ratePerHundred: z.number().nullable()
});

export const attachRateSchema = z.object({
  storeId: z.string().uuid(),
  periodStart: z.string(),
  periodEnd: z.string(),
  formulaVersion: z.string(),
  dataQuality: z.object({
    unmatchedNumeratorItemCount: z.number().int().nonnegative(),
    ambiguousWarrantyItemCount: z.number().int().nonnegative(),
    unknownDeviceConditionItemCount: z.number().int().nonnegative()
  }),
  rates: z.array(attachRateEntrySchema)
});

export type AttachRate = z.infer<typeof attachRateSchema>;

export const performancePlanSchema = z.object({
  id: z.string().uuid(),
  storeId: z.string().uuid(),
  planMonth: z.string(),
  revenueTarget: z.number(),
  accessoryShareTarget: z.number(),
  serviceShareTarget: z.number(),
  additionalShareTarget: z.number(),
  updatedBy: z.string().uuid(),
  version: z.number().int().nonnegative(),
  updatedAt: z.string()
});

const planDirectionSchema = z.object({
  code: z.string(),
  criterionType: z.string(),
  actualAmount: z.number(),
  targetAmount: z.number(),
  amountCompletionPercent: z.number().nullable(),
  currentDailyPace: z.number(),
  expectedAmountToDate: z.number(),
  paceGapAmount: z.number(),
  projectedAmount: z.number(),
  projectedAmountCompletionPercent: z.number().nullable(),
  remainingAmount: z.number(),
  requiredPerRemainingDay: z.number().nullable(),
  actualSharePercent: z.number().nullable(),
  targetSharePercent: z.number().nullable(),
  shareGapPercentagePoints: z.number().nullable(),
  criterionCompletionPercent: z.number().nullable(),
  achieved: z.boolean(),
  status: z.string()
});

export const planProgressSchema = z.object({
  storeId: z.string().uuid(),
  periodStart: z.string(),
  periodEnd: z.string(),
  asOfDate: z.string(),
  totalDays: z.number().int().positive(),
  elapsedDays: z.number().int().nonnegative(),
  remainingDays: z.number().int().nonnegative(),
  formulaVersion: z.string(),
  plan: performancePlanSchema,
  dataQuality: z.object({
    freshnessStatus: z.string(),
    dataThroughDate: z.string().nullable(),
    completeThroughAsOf: z.boolean(),
    classificationComplete: z.boolean(),
    unmappedItemCount: z.number().int().nonnegative(),
    openQualityIssueCount: z.number().int().nonnegative()
  }),
  achievedDirectionCount: z.number().int().nonnegative(),
  allDirectionsAchieved: z.boolean(),
  focusDirections: z.array(z.string()),
  directions: z.array(planDirectionSchema),
  calculatedAt: z.string()
});

export type PlanProgress = z.infer<typeof planProgressSchema>;
export type PlanDirection = z.infer<typeof planDirectionSchema>;
export type PerformancePlan = z.infer<typeof performancePlanSchema>;

export interface PerformancePlanInput {
  revenueTarget: number;
  accessoryShareTarget: number;
  serviceShareTarget: number;
  additionalShareTarget: number;
}

export const employeeShiftSchema = z.object({
  id: z.string().uuid(),
  employeeId: z.string().uuid(),
  employeeName: z.string(),
  workDate: z.string(),
  workedHours: z.number().positive().max(11),
  active: z.boolean(),
  version: z.number().int().nonnegative()
});

export const employeeShiftListSchema = z.array(employeeShiftSchema);
export type EmployeeShift = z.infer<typeof employeeShiftSchema>;

export const workScheduleDaySchema = z.object({
  storeId: z.string().uuid(),
  workDate: z.string(),
  revision: z.number().int().nonnegative(),
  shifts: employeeShiftListSchema
});
export type WorkScheduleDay = z.infer<typeof workScheduleDaySchema>;
export interface WorkShiftInput { employeeId: string; workedHours: number; }

const periodQualityIssueSchema = z.object({
  key: z.string(),
  area: z.string(),
  code: z.string(),
  severity: z.string(),
  message: z.string(),
  affectedCount: z.number().int().nullable(),
  recommendedAction: z.string()
});

export const periodQualitySchema = z.object({
  storeId: z.string().uuid(),
  periodMonth: z.string(),
  periodStart: z.string(),
  periodEnd: z.string(),
  asOfDate: z.string(),
  status: z.string(),
  readyForDecisions: z.boolean(),
  areas: z.array(z.object({
    code: z.string(),
    status: z.string(),
    ready: z.boolean(),
    issueCount: z.number().int().nonnegative(),
    errorCount: z.number().int().nonnegative(),
    warningCount: z.number().int().nonnegative(),
    infoCount: z.number().int().nonnegative()
  })),
  issues: z.array(periodQualityIssueSchema),
  checkedAt: z.string()
}).passthrough();

export type PeriodQuality = z.infer<typeof periodQualitySchema>;

const ratingScoreBreakdownSchema = z.object({
  contributionScore: z.number().nullable(),
  contributionWeightedPoints: z.number().nullable(),
  efficiencyScore: z.number().nullable(),
  efficiencyWeightedPoints: z.number().nullable(),
  structureScore: z.number().nullable(),
  structureWeightedPoints: z.number().nullable(),
  attachScore: z.number().nullable(),
  attachWeightedPoints: z.number().nullable(),
  coveragePercent: z.number(),
  overallScore: z.number().nullable()
});

const employeeAttachRatingEntrySchema = z.object({
  metricCode: z.string(),
  numeratorCategoryCode: z.string(),
  denominatorCode: z.string(),
  numeratorQuantity: z.number(),
  denominatorQuantity: z.number(),
  ratePercent: z.number().nullable(),
  storeRatePercent: z.number().nullable(),
  includedInScore: z.boolean(),
  score: z.number().nullable()
});

export const employeeRatingEntrySchema = z.object({
  employeeId: z.string().uuid(),
  displayName: z.string(),
  employeeActive: z.boolean(),
  assignmentActive: z.boolean(),
  participatesInRanking: z.boolean(),
  ratingEligible: z.boolean(),
  shiftCount: z.number().int().nonnegative(),
  workedHours: z.number().nonnegative(),
  netRevenue: z.number(),
  storeRevenueSharePercent: z.number().nullable(),
  revenuePerShift: z.number().nullable(),
  revenuePerHour: z.number().nullable(),
  accessoryRevenue: z.number(),
  accessorySharePercent: z.number().nullable(),
  serviceRevenue: z.number(),
  serviceSharePercent: z.number().nullable(),
  additionalRevenue: z.number(),
  additionalSharePercent: z.number().nullable(),
  scores: ratingScoreBreakdownSchema,
  ranked: z.boolean(),
  rank: z.number().int().positive().nullable(),
  attachRates: z.array(employeeAttachRatingEntrySchema)
});

const employeeAttachRateChangeSchema = z.object({
  metricCode: z.string(),
  previousRate: z.number().nullable(),
  currentRate: z.number().nullable(),
  change: z.number().nullable()
});

const employeeRatingDynamicsSchema = z.object({
  previousRank: z.number().int().positive().nullable(),
  currentRank: z.number().int().positive().nullable(),
  rankImprovement: z.number().int().nullable(),
  overallScoreChange: z.number().nullable(),
  revenueChange: z.number().nullable(),
  revenuePerHourChange: z.number().nullable(),
  accessoryShareChange: z.number().nullable(),
  serviceShareChange: z.number().nullable(),
  additionalShareChange: z.number().nullable(),
  attachRateChanges: z.array(employeeAttachRateChangeSchema)
});

const ratingFormulaSchema = z.object({
  version: z.string(),
  contributionWeight: z.number(),
  efficiencyWeight: z.number(),
  structureWeight: z.number(),
  attachWeight: z.number(),
  accessoryStructureWeight: z.number(),
  serviceStructureWeight: z.number(),
  minimumAttachDenominator: z.number(),
  scoreCap: z.number().positive(),
  minimumCoveragePercent: z.number()
});

const ratingPlanContextSchema = z.object({
  complete: z.boolean(),
  coveragePercent: z.number(),
  proratedRevenueTarget: z.number().nullable(),
  accessoryShareTarget: z.number().nullable(),
  serviceShareTarget: z.number().nullable(),
  additionalShareTarget: z.number().nullable(),
  actualStoreRevenue: z.number(),
  revenueAchievementPercent: z.number().nullable()
});

const employeeRatingHistorySchema = z.object({
  status: z.string(),
  snapshotId: z.string().uuid().nullable(),
  finalizedAt: z.string().nullable(),
  finalizedBy: z.string().uuid().nullable(),
  finalizedByName: z.string().nullable()
});

export const employeeRatingResultSchema = z.object({
  storeId: z.string().uuid(),
  periodStart: z.string(),
  periodEnd: z.string(),
  formula: ratingFormulaSchema,
  plan: ratingPlanContextSchema,
  employees: z.array(employeeRatingEntrySchema),
  history: employeeRatingHistorySchema
});

const employeeDirectoryEntrySchema = z.object({
  current: employeeRatingEntrySchema,
  dynamics: employeeRatingDynamicsSchema
});

export const employeeDirectorySchema = z.object({
  storeId: z.string().uuid(),
  periodStart: z.string(),
  periodEnd: z.string(),
  previousPeriodStart: z.string(),
  previousPeriodEnd: z.string(),
  employees: z.array(employeeDirectoryEntrySchema)
});

export const employeeCardSchema = z.object({
  storeId: z.string().uuid(),
  employeeId: z.string().uuid(),
  periodStart: z.string(),
  periodEnd: z.string(),
  previousPeriodStart: z.string(),
  previousPeriodEnd: z.string(),
  formula: ratingFormulaSchema,
  plan: ratingPlanContextSchema,
  current: employeeRatingEntrySchema,
  previous: employeeRatingEntrySchema.nullable(),
  dynamics: employeeRatingDynamicsSchema,
  payroll: z.object({
    run: z.object({
      id: z.string().uuid(), periodMonth: z.string(), revision: z.number().int().positive(), status: z.string(),
      freshness: z.object({ status: z.string(), requiresRecalculation: z.boolean(), reasons: z.array(z.string()), checkedAt: z.string() })
    }).passthrough(),
    statement: z.object({
      id: z.string().uuid(), employeeId: z.string().uuid(), employeeName: z.string(),
      shiftCount: z.number().int().nonnegative(), workedHours: z.number().nonnegative(),
      earnedAmount: z.number(), advanceAmount: z.number(), penaltyAmount: z.number(),
      inventoryAmount: z.number(), taxAmount: z.number(), payableAmount: z.number()
    })
  }).nullable()
});

export const employeeRatingSettingSchema = z.object({
  employeeId: z.string().uuid(),
  displayName: z.string(),
  employeeActive: z.boolean(),
  assignmentActive: z.boolean(),
  participatesInRanking: z.boolean(),
  version: z.number().int().nonnegative(),
  updatedAt: z.string()
});

export const employeeRatingSettingsSchema = z.array(employeeRatingSettingSchema);

export type EmployeeAttachRatingEntry = z.infer<typeof employeeAttachRatingEntrySchema>;
export type EmployeeRatingEntry = z.infer<typeof employeeRatingEntrySchema>;
export type EmployeeRatingDynamics = z.infer<typeof employeeRatingDynamicsSchema>;
export type EmployeeRatingResult = z.infer<typeof employeeRatingResultSchema>;
export type EmployeeDirectoryEntry = z.infer<typeof employeeDirectoryEntrySchema>;
export type EmployeeDirectory = z.infer<typeof employeeDirectorySchema>;
export type EmployeeCard = z.infer<typeof employeeCardSchema>;
export type EmployeeRatingSetting = z.infer<typeof employeeRatingSettingSchema>;

const payrollRunStatusSchema = forwardCompatibleEnum(["CALCULATED", "APPROVED", "PAID"]);
const payrollFreshnessStatusSchema = forwardCompatibleEnum(["CURRENT", "STALE"]);

export const payrollPlanResultSchema = z.object({
  revenueTarget: z.number(),
  actualRevenue: z.number(),
  revenueAchieved: z.boolean(),
  accessoryShareTarget: z.number(),
  actualAccessoryTurnover: z.number(),
  actualAccessorySharePercent: z.number().nullable(),
  accessoryAchieved: z.boolean(),
  serviceShareTarget: z.number(),
  actualServiceTurnover: z.number(),
  actualServiceSharePercent: z.number().nullable(),
  serviceAchieved: z.boolean()
});

const payrollAppliedRatesSchema = z.object({
  accessoryPercentage: z.number(),
  servicePercentage: z.number(),
  tier1Rate: z.number(),
  tier2Rate: z.number()
});

const payrollSchemeSchema = z.object({
  id: z.string().uuid(),
  code: z.string(),
  effectiveFrom: z.string(),
  achievedPercentage: z.number(),
  missedPercentage: z.number(),
  achievedTier1Rate: z.number(),
  missedTier1Rate: z.number(),
  achievedTier2Rate: z.number(),
  missedTier2Rate: z.number(),
  advanceAmount: z.number()
});

const payrollFreshnessSchema = z.object({
  status: payrollFreshnessStatusSchema,
  requiresRecalculation: z.boolean(),
  reasons: z.array(z.string()),
  checkedAt: z.string()
});

const payrollUnmappedProductSchema = z.object({
  productId: z.string().uuid(),
  productName: z.string(),
  analyticsCategoryCode: z.string().nullable(),
  firstSaleDate: z.string(),
  lastSaleDate: z.string(),
  saleItemCount: z.number().int().nonnegative(),
  returnItemCount: z.number().int().nonnegative(),
  netQuantity: z.number(),
  netRevenue: z.number(),
  suggestedCategoryCode: z.string().nullable(),
  suggestionReason: z.string().nullable()
});

const payrollMissingCostSchema = z.object({
  payrollDate: z.string(),
  documentId: z.string().uuid(),
  documentExternalId: z.string(),
  returnDocument: z.boolean(),
  productId: z.string().uuid(),
  productName: z.string(),
  payrollCategoryCode: z.string(),
  quantity: z.number(),
  netAmount: z.number()
});

const payrollShiftIssueSchema = z.object({ workDate: z.string(), fundAmount: z.number() });

export const payrollReadinessSchema = z.object({
  storeId: z.string().uuid(),
  periodMonth: z.string(),
  status: forwardCompatibleEnum(["READY", "NEEDS_CORRECTION", "BLOCKED"]),
  canCalculate: z.boolean(),
  canApprove: z.boolean(),
  planPresent: z.boolean(),
  schemePresent: z.boolean(),
  planResult: payrollPlanResultSchema.nullable(),
  salesDayCount: z.number().int().nonnegative(),
  scheduledDayCount: z.number().int().nonnegative(),
  unmappedItemCount: z.number().int().nonnegative(),
  missingCostItemCount: z.number().int().nonnegative(),
  daysWithoutShift: z.number().int().nonnegative(),
  unmappedProducts: z.array(payrollUnmappedProductSchema),
  missingCosts: z.array(payrollMissingCostSchema),
  shiftIssues: z.array(payrollShiftIssueSchema)
});

const payrollPreviewAllocationSchema = z.object({
  employeeId: z.string().uuid(), employeeName: z.string(), workedHours: z.number(), amount: z.number()
});

const nullableAmount = z.number().nullable();
const payrollPreviewDaySchema = z.object({
  workDate: z.string(),
  accessoryTurnover: z.number(),
  serviceTurnover: z.number(),
  playstationGrossProfit: nullableAmount,
  paidRepairGrossProfit: nullableAmount,
  tier1Quantity: z.number(),
  tier2Quantity: z.number(),
  accessoryReward: z.number(),
  serviceReward: z.number(),
  playstationReward: nullableAmount,
  paidRepairReward: nullableAmount,
  tier1Reward: z.number(),
  tier2Reward: z.number(),
  fundAmount: nullableAmount,
  shiftEmployeeCount: z.number().int().nonnegative(),
  calculationComplete: z.boolean(),
  allocations: z.array(payrollPreviewAllocationSchema)
});

const payrollStatementSchema = z.object({
  id: z.string().uuid().optional(),
  employeeId: z.string().uuid(),
  employeeName: z.string(),
  shiftCount: z.number().int().nonnegative(),
  workedHours: z.number().nonnegative(),
  earnedAmount: z.number(),
  advanceAmount: z.number(),
  penaltyAmount: z.number(),
  inventoryAmount: z.number(),
  taxAmount: z.number(),
  payableAmount: z.number()
});

export const payrollPreviewSchema = z.object({
  storeId: z.string().uuid(),
  periodMonth: z.string(),
  persisted: z.boolean(),
  planResult: payrollPlanResultSchema,
  scheme: payrollSchemeSchema,
  readiness: payrollReadinessSchema,
  actualScenario: z.object({
    appliedRates: payrollAppliedRatesSchema,
    calculationComplete: z.boolean(),
    totalFundAmount: nullableAmount,
    totalPayableAmount: z.number(),
    days: z.array(payrollPreviewDaySchema),
    employees: z.array(payrollStatementSchema)
  })
});

export const payrollRunSummarySchema = z.object({
  id: z.string().uuid(), storeId: z.string().uuid(), periodMonth: z.string(),
  revision: z.number().int().positive(), supersedesRunId: z.string().uuid().nullable(),
  revisionReason: z.string().nullable(), status: payrollRunStatusSchema,
  freshness: payrollFreshnessSchema, planResult: payrollPlanResultSchema,
  calculationComplete: z.boolean(), unmappedItemCount: z.number().int().nonnegative(),
  missingCostItemCount: z.number().int().nonnegative(), daysWithoutShift: z.number().int().nonnegative(),
  createdBy: z.string().uuid(), approvedBy: z.string().uuid().nullable(), approvedAt: z.string().nullable(),
  paidBy: z.string().uuid().nullable(), paidAt: z.string().nullable(), version: z.number().int().nonnegative(),
  createdAt: z.string(), updatedAt: z.string()
});

const payrollDailyPoolSchema = payrollPreviewDaySchema.omit({ allocations: true }).extend({
  id: z.string().uuid(), accessoryPercentageRate: z.number(), servicePercentageRate: z.number(),
  tier1Rate: z.number(), tier2Rate: z.number(), unmappedItemCount: z.number().int().nonnegative(),
  missingCostItemCount: z.number().int().nonnegative()
});

const payrollDailyAllocationSchema = payrollPreviewAllocationSchema.extend({
  id: z.string().uuid(), workDate: z.string()
});

export const payrollAdjustmentSchema = z.object({
  id: z.string().uuid(), employeeId: z.string().uuid(), employeeName: z.string(),
  type: z.string(), amount: z.number(), reason: z.string(), active: z.boolean(),
  createdBy: z.string().uuid(), voidedBy: z.string().uuid().nullable(), voidReason: z.string().nullable(),
  voidedAt: z.string().nullable(), version: z.number().int().nonnegative(), createdAt: z.string()
});

const payrollEventSchema = z.object({
  id: z.string().uuid(), type: z.string(), actorId: z.string().uuid(), details: z.string().nullable(), createdAt: z.string()
});

export const payrollRunDetailSchema = z.object({
  run: payrollRunSummarySchema, scheme: payrollSchemeSchema,
  dailyPools: z.array(payrollDailyPoolSchema), dailyAllocations: z.array(payrollDailyAllocationSchema),
  adjustments: z.array(payrollAdjustmentSchema), statements: z.array(payrollStatementSchema),
  events: z.array(payrollEventSchema)
});

export const payrollRunListItemSchema = z.object({
  id: z.string().uuid(),
  storeId: z.string().uuid(),
  periodMonth: z.string(),
  revision: z.number().int().positive(),
  supersedesRunId: z.string().uuid().nullable(),
  revisionReason: z.string().nullable(),
  status: payrollRunStatusSchema,
  createdAt: z.string()
});

export const payrollRunListSchema = pageResponseSchema(payrollRunListItemSchema);

const payrollRevisionSummaryChangeSchema = z.object({
  employeeId: z.string().uuid(), employeeName: z.string(), previousEarnedAmount: z.number(),
  currentEarnedAmount: z.number(), earnedChange: z.number(), previousPayableAmount: z.number(),
  currentPayableAmount: z.number(), payableChange: z.number(), previousDeductionAmount: z.number(),
  currentDeductionAmount: z.number(), deductionChange: z.number(), reasons: z.array(z.string())
});
const payrollRevisionDayChangeSchema = z.object({
  workDate: z.string(), previousFundAmount: nullableAmount, currentFundAmount: nullableAmount,
  fundChange: nullableAmount, previousShiftEmployeeCount: z.number().int().nonnegative(),
  currentShiftEmployeeCount: z.number().int().nonnegative(), reasons: z.array(z.string())
});
export const payrollRevisionComparisonSchema = z.object({
  storeId: z.string().uuid(), periodMonth: z.string(), previousRun: payrollRunSummarySchema,
  currentRun: payrollRunSummarySchema, revenuePlanStatusChanged: z.boolean(),
  accessoryPlanStatusChanged: z.boolean(), servicePlanStatusChanged: z.boolean(), schemeChanged: z.boolean(),
  previousTotalFund: nullableAmount, currentTotalFund: nullableAmount, totalFundChange: nullableAmount,
  previousTotalPayable: z.number(), currentTotalPayable: z.number(), totalPayableChange: z.number(),
  employeeChanges: z.array(payrollRevisionSummaryChangeSchema), dayChanges: z.array(payrollRevisionDayChangeSchema)
});

export type PayrollReadiness = z.infer<typeof payrollReadinessSchema>;
export type PayrollPreview = z.infer<typeof payrollPreviewSchema>;
export type PayrollPlanResult = z.infer<typeof payrollPlanResultSchema>;
export type PayrollRunSummary = z.infer<typeof payrollRunSummarySchema>;
export type PayrollRunListItem = z.infer<typeof payrollRunListItemSchema>;
export type PayrollRunDetail = z.infer<typeof payrollRunDetailSchema>;
export type PayrollStatement = z.infer<typeof payrollStatementSchema>;
export type PayrollDailyPool = z.infer<typeof payrollDailyPoolSchema>;
export type PayrollDailyAllocation = z.infer<typeof payrollDailyAllocationSchema>;
export type PayrollAdjustment = z.infer<typeof payrollAdjustmentSchema>;
export type PayrollRevisionComparison = z.infer<typeof payrollRevisionComparisonSchema>;
export type PayrollAdjustmentType = "PENALTY" | "INVENTORY" | "TAX";

export interface PayrollAdjustmentInput { employeeId: string; type: PayrollAdjustmentType; amount: number; reason: string; runVersion: number; }
export interface PayrollVoidAdjustmentInput { reason: string; runVersion: number; adjustmentVersion: number; }
const reportActorSchema = z.object({
  id: z.string().uuid(),
  displayName: z.string()
});

const reportCoverageSchema = forwardCompatibleEnum(["COMPLETE", "PARTIAL_FIRST_YEAR"]);

const reportHeaderSchema = z.object({
  storeId: z.string().uuid(),
  storeName: z.string(),
  storeAddress: z.string().nullable(),
  reportingStartedOn: z.string(),
  periodStart: z.string(),
  periodEnd: z.string(),
  coverage: reportCoverageSchema,
  templateVersion: z.string(),
  dataContractVersion: z.string(),
  generatedAt: z.string(),
  finalizedBy: reportActorSchema.nullable()
});

export const reportSummarySchema = z.object({
  id: z.string().uuid(),
  storeId: z.string().uuid(),
  type: forwardCompatibleEnum(["MONTHLY", "ANNUAL"]),
  periodStart: z.string(),
  periodEnd: z.string(),
  coverage: reportCoverageSchema,
  status: forwardCompatibleEnum(["FINALIZED"]),
  revision: z.number().int().positive(),
  currentRevision: z.boolean(),
  supersedesReportId: z.string().uuid().nullable(),
  revisionReason: z.string().nullable(),
  payrollRunId: z.string().uuid().nullable(),
  templateVersion: z.string(),
  schemaVersion: z.number().int().positive(),
  finalizedAt: z.string(),
  finalizedBy: reportActorSchema.nullable()
});

export const reportSummaryListSchema = pageResponseSchema(reportSummarySchema);
export const reportYearsSchema = z.array(z.number().int());

const reportAverageKpiSchema = z.object({
  formulaVersion: z.string(),
  averageReceipt: averageMetricSnapshotSchema,
  additionalRevenuePerPhone: averageMetricSnapshotSchema,
  categoryAveragePrices: z.array(z.object({
    categoryCode: z.string(),
    categoryName: z.string(),
    categoryActive: z.boolean(),
    averageUnitPrice: averageMetricSnapshotSchema
  }))
});

export const monthlyReportPayloadSchema = z.object({
  schemaVersion: z.number().int().positive(),
  header: reportHeaderSchema,
  storeKpi: storeKpiSchema,
  categoryKpi: categoryKpiSchema,
  averageKpi: reportAverageKpiSchema,
  attachRates: attachRateSchema,
  planProgress: planProgressSchema,
  employeeRating: employeeRatingResultSchema,
  payroll: payrollRunDetailSchema,
  quality: periodQualitySchema
});

const annualStoreTotalsSchema = z.object({
  monthCount: z.number().int().positive(),
  netRevenue: z.number(),
  netQuantity: z.number(),
  costAmount: z.number().nullable(),
  grossProfit: z.number().nullable(),
  marginPercent: z.number().nullable(),
  payrollEarnedAmount: z.number(),
  payrollPayableAmount: z.number()
});

const annualCategoryTotalsSchema = z.object({
  categoryCode: z.string(),
  categoryName: z.string(),
  netRevenue: z.number(),
  netQuantity: z.number(),
  costAmount: z.number().nullable(),
  grossProfit: z.number().nullable(),
  marginPercent: z.number().nullable()
});

const annualAttachRateTotalsSchema = z.object({
  metricCode: z.string(),
  numeratorQuantity: z.number(),
  denominatorQuantity: z.number(),
  ratePerHundred: z.number().nullable()
});

const annualEmployeeTotalsSchema = z.object({
  employeeId: z.string().uuid(),
  employeeName: z.string(),
  shiftCount: z.number().int().nonnegative(),
  workedHours: z.number(),
  netRevenue: z.number(),
  earnedAmount: z.number(),
  payableAmount: z.number()
});

export const annualReportPayloadSchema = z.object({
  schemaVersion: z.number().int().positive(),
  header: reportHeaderSchema,
  totals: annualStoreTotalsSchema,
  categories: z.array(annualCategoryTotalsSchema),
  attachRates: z.array(annualAttachRateTotalsSchema),
  employees: z.array(annualEmployeeTotalsSchema),
  months: z.array(z.object({
    snapshotId: z.string().uuid(),
    revision: z.number().int().positive(),
    payloadHash: z.string().regex(/^[0-9a-f]{64}$/),
    report: monthlyReportPayloadSchema
  }))
});

export const reportDetailSchema = z.object({
  report: reportSummarySchema,
  monthly: monthlyReportPayloadSchema.nullable(),
  annual: annualReportPayloadSchema.nullable()
});

export type ReportType = "MONTHLY" | "ANNUAL";
export type ReportSummary = z.infer<typeof reportSummarySchema>;
export type MonthlyReportPayload = z.infer<typeof monthlyReportPayloadSchema>;
export type AnnualReportPayload = z.infer<typeof annualReportPayloadSchema>;
export type ReportDetail = z.infer<typeof reportDetailSchema>;
