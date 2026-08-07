import { z } from "zod";
import { forwardCompatibleEnum } from "./enumSchema";

const insightStateSchema = forwardCompatibleEnum([
  "READY",
  "PREPARING",
  "DELAYED",
  "UNAVAILABLE"
]);

const insightReasonSchema = forwardCompatibleEnum([
  "READY",
  "WAITING_FOR_DATA",
  "ANALYSIS_IN_PROGRESS",
  "SOURCE_DELAYED",
  "ANALYSIS_DELAYED",
  "DATA_QUALITY_BLOCKED",
  "ANALYSIS_TEMPORARILY_UNAVAILABLE",
  "PERIOD_NOT_AVAILABLE"
]);

const revisionStateSchema = forwardCompatibleEnum([
  "CURRENT",
  "UPDATING",
  "UPDATE_DELAYED"
]);

const narrativeBlockSchema = z.object({
  text: z.string().min(1).max(600),
  evidenceRefs: z.array(z.string().min(3).max(160)).max(8)
}).passthrough();

const insightBlockSchema = z.object({
  kind: z.string(),
  theme: z.string(),
  candidateRef: z.string().nullish().transform((value) => value ?? null),
  title: z.string().min(1).max(120),
  summary: z.string().min(1).max(600),
  evidenceRefs: z.array(z.string().min(3).max(160)).max(8)
}).passthrough();

const actionSchema = z.object({
  type: z.string(),
  title: z.string().min(1).max(120),
  summary: z.string().min(1).max(600),
  evidenceRefs: z.array(z.string().min(3).max(160)).max(8),
  targetScope: z.string(),
  targetEmployeeRefs: z.array(z.string()).max(10),
  horizon: z.string()
}).passthrough();

const categoryInterpretationSchema = z.object({
  summary: narrativeBlockSchema.nullish().transform((value) => value ?? null),
  growthDrivers: z.array(insightBlockSchema).max(20),
  declineDrivers: z.array(insightBlockSchema).max(20),
  mixInsights: z.array(insightBlockSchema).max(20)
}).passthrough();

const additionalSalesInterpretationSchema = z.object({
  summary: narrativeBlockSchema.nullish().transform((value) => value ?? null),
  revenueInsights: z.array(insightBlockSchema).max(20),
  attachRateInsights: z.array(insightBlockSchema).max(20),
  opportunities: z.array(insightBlockSchema).max(20)
}).passthrough();

const storeInsightSchema = z.object({
  headline: narrativeBlockSchema,
  resultSummary: narrativeBlockSchema.nullish().transform((value) => value ?? null),
  dynamicsSummary: narrativeBlockSchema.nullish().transform((value) => value ?? null),
  categoryPerformance: categoryInterpretationSchema.nullish().transform((value) => value ?? null),
  additionalSalesPerformance: additionalSalesInterpretationSchema.nullish().transform((value) => value ?? null),
  planOutlook: narrativeBlockSchema.nullish().transform((value) => value ?? null),
  strength: insightBlockSchema.nullish().transform((value) => value ?? null),
  attentionArea: insightBlockSchema.nullish().transform((value) => value ?? null),
  primaryRisk: insightBlockSchema.nullish().transform((value) => value ?? null),
  recommendedActions: z.array(actionSchema).max(10)
}).passthrough();

const teamInsightSchema = z.object({
  summary: narrativeBlockSchema,
  highlights: z.array(insightBlockSchema).max(20),
  competencyLeaders: z.array(z.object({
    competencyCode: z.string(),
    employeeRefs: z.array(z.string()).max(10),
    employeeNames: z.array(z.string()).max(10).default([]),
    summary: z.string().min(1).max(600),
    evidenceRefs: z.array(z.string()).max(8)
  }).passthrough()).max(20),
  mostImproved: z.array(z.object({
    employeeRef: z.string(),
    displayName: z.string().nullish().transform((value) => value ?? null),
    kind: z.string(),
    summary: z.string().min(1).max(600),
    evidenceRefs: z.array(z.string()).max(8)
  }).passthrough()).max(10),
  learningOpportunities: z.array(z.object({
    competencyCode: z.string(),
    mentorEmployeeRefs: z.array(z.string()).max(10),
    targetEmployeeRefs: z.array(z.string()).max(10),
    mentorNames: z.array(z.string()).max(10).default([]),
    targetNames: z.array(z.string()).max(10).default([]),
    summary: z.string().min(1).max(600),
    evidenceRefs: z.array(z.string()).max(8)
  }).passthrough()).max(20)
}).passthrough();

const employeeInsightSchema = z.object({
  analysisStatus: z.string(),
  headline: narrativeBlockSchema,
  workloadContext: narrativeBlockSchema.nullish().transform((value) => value ?? null),
  performanceSummary: narrativeBlockSchema.nullish().transform((value) => value ?? null),
  dynamicsSummary: narrativeBlockSchema.nullish().transform((value) => value ?? null),
  categoryPerformance: z.object({
    summary: narrativeBlockSchema.nullish().transform((value) => value ?? null),
    strengths: z.array(insightBlockSchema).max(20),
    attentionAreas: z.array(insightBlockSchema).max(20),
    dynamics: z.array(insightBlockSchema).max(20)
  }).passthrough().nullish().transform((value) => value ?? null),
  additionalSalesPerformance: additionalSalesInterpretationSchema.nullish().transform((value) => value ?? null),
  strength: insightBlockSchema.nullish().transform((value) => value ?? null),
  attentionArea: insightBlockSchema.nullish().transform((value) => value ?? null),
  primaryRisk: insightBlockSchema.nullish().transform((value) => value ?? null),
  recommendedActions: z.array(actionSchema).max(10),
  dataLimitations: z.array(z.unknown()).max(20)
}).passthrough();

const dataLimitationSchema = z.object({
  code: z.string().min(3).max(100),
  scope: z.string(),
  employeeRef: z.string().nullish().transform((value) => value ?? null),
  categoryCode: z.string().nullish().transform((value) => value ?? null),
  impact: z.string(),
  affectedSections: z.array(z.string()).max(10),
  summary: z.string().min(1).max(300),
  evidenceRefs: z.array(z.string()).max(8)
}).passthrough();

export const weeklyInsightSchema = z.object({
  period: z.object({
    periodStart: z.string().date(),
    periodEnd: z.string().date(),
    timezone: z.string().min(1).max(100)
  }),
  state: insightStateSchema,
  reasonCode: insightReasonSchema,
  message: z.string().min(1).max(300),
  statusUpdatedAt: z.string().datetime({ offset: true }),
  nextRefreshAt: z.string().datetime({ offset: true }).nullish().transform((value) => value ?? null),
  interpretationId: z.string().uuid().nullish().transform((value) => value ?? null),
  revision: z.number().int().positive().nullish().transform((value) => value ?? null),
  publishedAt: z.string().datetime({ offset: true }).nullish().transform((value) => value ?? null),
  sourceDataUpdatedAt: z.string().datetime({ offset: true }).nullish().transform((value) => value ?? null),
  revisionState: revisionStateSchema.nullish().transform((value) => value ?? null),
  content: z.object({
    store: storeInsightSchema,
    teamInsights: teamInsightSchema,
    employees: z.array(z.object({
      employeeId: z.string().uuid(),
      displayName: z.string().min(1).max(200),
      analysisStatus: z.string(),
      insight: employeeInsightSchema
    })).max(10),
    dataLimitations: z.array(dataLimitationSchema).max(20)
  }).nullish().transform((value) => value ?? null),
  fallback: z.object({
    title: z.string().min(1).max(120),
    summary: z.string().min(1).max(600),
    qualityStatus: forwardCompatibleEnum(["READY", "PARTIAL", "BLOCKED"]),
    dataLimitationCodes: z.array(z.string().min(3).max(100)).max(20)
  }).nullish().transform((value) => value ?? null)
}).superRefine((value, context) => {
  if (value.state === "READY" && value.content === null) {
    context.addIssue({
      code: "custom",
      path: ["content"],
      message: "READY weekly insight must include content"
    });
  }
  if (value.state !== "READY" && value.content !== null) {
    context.addIssue({
      code: "custom",
      path: ["content"],
      message: "Non-ready weekly insight must not include content"
    });
  }
});

export type WeeklyInsight = z.infer<typeof weeklyInsightSchema>;
export type WeeklyInsightContent = NonNullable<WeeklyInsight["content"]>;
export type WeeklyInsightStore = WeeklyInsightContent["store"];
export type WeeklyInsightEmployee = WeeklyInsightContent["employees"][number];
