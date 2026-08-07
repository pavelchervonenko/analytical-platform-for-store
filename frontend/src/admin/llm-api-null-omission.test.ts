import { describe, expect, it } from "vitest";
import { llmOperationsSchema } from "./llm-api";

describe("LLM operations nullable serialization", () => {
  it("normalizes optional backend null fields", () => {
    const result = llmOperationsSchema.parse({
      generatedAt: "2026-08-03T05:00:00Z",
      configuration: {
        snapshotsEnabled: false,
        generationEnabled: false,
        publicationEnabled: false,
        providerConfigured: false
      },
      summary: {
        attentionLevel: "NORMAL",
        pending: 0,
        waitingRetry: 0,
        running: 0,
        overdueRunning: 0,
        failed: 0,
        validationFailed: 0,
        succeededLast30Days: 0,
        providerCallsLast30Days: 0,
        inputTokensLast30Days: 0,
        outputTokensLast30Days: 0,
        knownCostLast30Days: 0
      },
      incidents: []
    });

    expect(result.configuration.model).toBeNull();
    expect(result.summary.costCurrency).toBeNull();
    expect(result.summary.oldestReadyAt).toBeNull();
  });
});
