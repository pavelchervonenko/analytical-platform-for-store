import { describe, expect, it } from "vitest";
import { weeklyInsightSchema } from "./weeklyInsightContract";

describe("weekly insight nullable serialization", () => {
  it("accepts non-ready fields omitted by the backend JSON serializer", () => {
    const result = weeklyInsightSchema.parse({
      period: {
        periodStart: "2026-07-27",
        periodEnd: "2026-08-02",
        timezone: "Europe/Moscow"
      },
      state: "PREPARING",
      reasonCode: "ANALYSIS_IN_PROGRESS",
      message: "Анализируем результаты недели.",
      statusUpdatedAt: "2026-08-03T06:00:00Z",
      nextRefreshAt: "2026-08-03T06:00:15Z",
      sourceDataUpdatedAt: "2026-08-03T05:55:00Z"
    });

    expect(result.content).toBeNull();
    expect(result.fallback).toBeNull();
    expect(result.interpretationId).toBeNull();
    expect(result.revisionState).toBeNull();
  });
});
