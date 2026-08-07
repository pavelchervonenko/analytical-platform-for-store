import { z } from "zod";
import { apiClient } from "../api/client";
import { forwardCompatibleEnum } from "../api/enumSchema";

const attentionSchema = forwardCompatibleEnum(["NORMAL", "WARNING", "CRITICAL"]);
const statusSchema = forwardCompatibleEnum([
  "PENDING", "RUNNING", "WAITING_RETRY", "SUCCESS", "VALIDATION_FAILED",
  "FAILED", "SKIPPED", "CANCELLED"
]);

const llmJobIncidentSchema = z.object({
  jobId: z.string().uuid(),
  snapshotId: z.string().uuid(),
  storeId: z.string().uuid(),
  storeName: z.string(),
  periodStart: z.string().date(),
  periodEnd: z.string().date(),
  snapshotRevision: z.number().int().positive(),
  generationRevision: z.number().int().positive(),
  triggerType: z.string(),
  status: statusSchema,
  phase: z.string(),
  attemptCount: z.number().int().nonnegative(),
  transportRetryCount: z.number().int().nonnegative(),
  validationRetryCount: z.number().int().nonnegative(),
  nextAttemptAt: z.string().datetime({ offset: true }),
  deadlineAt: z.string().datetime({ offset: true }),
  cancelRequested: z.boolean(),
  terminalReasonCode: z.string().nullish().transform((value) => value ?? null),
  errorSummary: z.string().nullish().transform((value) => value ?? null),
  lastAttemptStatus: z.string().nullish().transform((value) => value ?? null),
  lastHttpStatus: z.number().int().nullish().transform((value) => value ?? null),
  updatedAt: z.string().datetime({ offset: true })
});

export const llmOperationsSchema = z.object({
  generatedAt: z.string().datetime({ offset: true }),
  configuration: z.object({
    snapshotsEnabled: z.boolean(),
    generationEnabled: z.boolean(),
    publicationEnabled: z.boolean(),
    providerConfigured: z.boolean(),
    model: z.string().nullish().transform((value) => value ?? null)
  }),
  summary: z.object({
    attentionLevel: attentionSchema,
    pending: z.number().int().nonnegative(),
    waitingRetry: z.number().int().nonnegative(),
    running: z.number().int().nonnegative(),
    overdueRunning: z.number().int().nonnegative(),
    failed: z.number().int().nonnegative(),
    validationFailed: z.number().int().nonnegative(),
    succeededLast30Days: z.number().int().nonnegative(),
    providerCallsLast30Days: z.number().int().nonnegative(),
    inputTokensLast30Days: z.number().int().nonnegative(),
    outputTokensLast30Days: z.number().int().nonnegative(),
    knownCostLast30Days: z.number().nonnegative(),
    costCurrency: z.string().nullish().transform((value) => value ?? null),
    oldestReadyAt: z.string().datetime({ offset: true }).nullish().transform((value) => value ?? null)
  }),
  incidents: z.array(llmJobIncidentSchema).max(100)
});

const manualLlmJobSchema = z.object({
  jobId: z.string().uuid(),
  snapshotId: z.string().uuid(),
  generationRevision: z.number().int().positive(),
  status: statusSchema,
  phase: z.string(),
  cancelRequested: z.boolean(),
  updatedAt: z.string().datetime({ offset: true })
});

export type LlmOperations = z.infer<typeof llmOperationsSchema>;
export type LlmJobIncident = LlmOperations["incidents"][number];
export type ManualLlmJob = z.infer<typeof manualLlmJobSchema>;

export const llmOperationsKey = ["admin", "llm-operations"] as const;

export const getLlmOperations = (): Promise<LlmOperations> => apiClient.request(
  "/api/admin/llm/operations?incidentLimit=50",
  { schema: llmOperationsSchema }
);

export const regenerateLlmInterpretation = (
  snapshotId: string,
  reason: string
): Promise<ManualLlmJob> => apiClient.request(
  `/api/admin/llm/snapshots/${encodeURIComponent(snapshotId)}/regenerate`,
  {
    method: "POST",
    body: { reason },
    idempotencyScope: `llm:regenerate:${snapshotId}`,
    schema: manualLlmJobSchema
  }
);

export const cancelLlmJob = (
  jobId: string,
  reason: string
): Promise<ManualLlmJob> => apiClient.request(
  `/api/admin/llm/jobs/${encodeURIComponent(jobId)}/cancel`,
  {
    method: "POST",
    body: { reason },
    idempotencyScope: `llm:cancel:${jobId}`,
    schema: manualLlmJobSchema
  }
);
