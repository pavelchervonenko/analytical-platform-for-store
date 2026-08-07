import { describe, expect, it } from "vitest";
import { llmOperationsSchema } from "./llm-api";

describe("LLM operations runtime contract", () => {
  it("accepts a sanitized operator projection", () => {
    const result = llmOperationsSchema.parse({
      generatedAt: "2026-08-03T05:00:00Z",
      configuration: {
        snapshotsEnabled: true,
        generationEnabled: true,
        publicationEnabled: true,
        providerConfigured: true,
        model: "yandexgpt-5.1"
      },
      summary: {
        attentionLevel: "WARNING",
        pending: 1,
        waitingRetry: 1,
        running: 0,
        overdueRunning: 0,
        failed: 0,
        validationFailed: 0,
        succeededLast30Days: 4,
        providerCallsLast30Days: 5,
        inputTokensLast30Days: 12000,
        outputTokensLast30Days: 3000,
        knownCostLast30Days: 12,
        costCurrency: "RUB",
        oldestReadyAt: "2026-08-03T04:59:00Z"
      },
      incidents: [{
        jobId: "30df06fb-71fe-4477-b6b9-bbc712b1ab20",
        snapshotId: "30df06fb-71fe-4477-b6b9-bbc712b1ab21",
        storeId: "30df06fb-71fe-4477-b6b9-bbc712b1ab22",
        storeName: "Магазин",
        periodStart: "2026-07-27",
        periodEnd: "2026-08-02",
        snapshotRevision: 1,
        generationRevision: 1,
        triggerType: "INITIAL",
        status: "WAITING_RETRY",
        phase: "CALL_PROVIDER",
        attemptCount: 1,
        transportRetryCount: 1,
        validationRetryCount: 0,
        nextAttemptAt: "2026-08-03T05:01:00Z",
        deadlineAt: "2026-08-03T05:05:00Z",
        cancelRequested: false,
        terminalReasonCode: null,
        errorSummary: "Безопасное описание",
        lastAttemptStatus: "TRANSIENT_FAILED",
        lastHttpStatus: 429,
        updatedAt: "2026-08-03T05:00:00Z"
      }]
    });

    expect(result.summary.inputTokensLast30Days).toBe(12000);
    expect(result.incidents[0]).not.toHaveProperty("responseBody");
  });
});
