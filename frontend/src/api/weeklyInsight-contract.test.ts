import canonicalReadyContent from "../../../docs/schemas/examples/weekly-interpretation-content-v1-ready.json";
import { describe, expect, it } from "vitest";
import { weeklyInsightSchema } from "./weeklyInsightContract";

const employeeIds = [
  "30df06fb-71fe-4477-b6b9-bbc712b1ab25",
  "30df06fb-71fe-4477-b6b9-bbc712b1ab26"
];

function readyResponse() {
  const content = structuredClone(canonicalReadyContent);
  return {
    period: {
      periodStart: "2026-07-20",
      periodEnd: "2026-07-26",
      timezone: "Europe/Moscow"
    },
    state: "READY",
    reasonCode: "READY",
    message: "Интерпретация готова.",
    statusUpdatedAt: "2026-07-27T04:30:00Z",
    nextRefreshAt: null,
    interpretationId: "30df06fb-71fe-4477-b6b9-bbc712b1ab20",
    revision: 1,
    publishedAt: "2026-07-27T04:30:00Z",
    sourceDataUpdatedAt: "2026-07-27T03:50:00Z",
    revisionState: "CURRENT",
    content: {
      store: content.store,
      teamInsights: content.teamInsights,
      employees: content.employees.map((insight, index) => {
        const projectedInsight = { ...insight };
        Reflect.deleteProperty(projectedInsight, "employeeRef");
        return {
          employeeId: employeeIds[index],
          displayName: `Сотрудник ${index + 1}`,
          analysisStatus: insight.analysisStatus,
          insight: projectedInsight
        };
      }),
      dataLimitations: content.dataLimitations
    },
    fallback: null
  };
}

describe("weekly insight runtime contract", () => {
  it("accepts a backend projection of the canonical LLM result", () => {
    const result = weeklyInsightSchema.parse(readyResponse());

    expect(result.state).toBe("READY");
    expect(result.content?.employees).toHaveLength(canonicalReadyContent.employees.length);
    expect(result.content?.employees[0]?.insight).not.toHaveProperty("employeeRef");
  });

  it("accepts omitted optional store sections from the flat v2 adapter", () => {
    const response = readyResponse();
    const projected: unknown = {
      ...response,
      content: {
        ...response.content,
        store: {
          ...response.content.store,
          resultSummary: null,
          dynamicsSummary: null,
          categoryPerformance: null,
          additionalSalesPerformance: null,
          planOutlook: null
        }
      }
    };

    const result = weeklyInsightSchema.parse(projected);

    expect(result.content?.store.resultSummary).toBeNull();
    expect(result.content?.store.categoryPerformance).toBeNull();
  });

  it("accepts the backend projection for an insufficient employee", () => {
    const response = readyResponse();
    const employee = response.content.employees[0];
    if (!employee) {
      throw new Error("Canonical example must contain an employee");
    }
    const insufficientResponse: unknown = {
      ...response,
      content: {
        ...response.content,
        employees: [{
          ...employee,
          analysisStatus: "INSUFFICIENT",
          insight: {
            ...employee.insight,
            analysisStatus: "INSUFFICIENT",
            performanceSummary: null,
            dynamicsSummary: null,
            categoryPerformance: {
              ...employee.insight.categoryPerformance,
              summary: null,
              strengths: [],
              attentionAreas: [],
              dynamics: []
            },
            additionalSalesPerformance: null,
            strength: null,
            attentionArea: null,
            primaryRisk: null,
            recommendedActions: []
          }
        }, ...response.content.employees.slice(1)]
      }
    };

    const result = weeklyInsightSchema.parse(insufficientResponse);
    expect(result.content?.employees[0]?.insight.categoryPerformance?.summary).toBeNull();
  });

  it("accepts an unavailable state with a safe deterministic fallback", () => {
    const result = weeklyInsightSchema.parse({
      period: {
        periodStart: "2026-07-20",
        periodEnd: "2026-07-26",
        timezone: "Europe/Moscow"
      },
      state: "UNAVAILABLE",
      reasonCode: "DATA_QUALITY_BLOCKED",
      message: "Интерпретация недоступна из-за качества исходных данных.",
      statusUpdatedAt: "2026-07-27T04:30:00Z",
      nextRefreshAt: null,
      interpretationId: null,
      revision: null,
      publishedAt: null,
      sourceDataUpdatedAt: "2026-07-27T03:50:00Z",
      revisionState: null,
      content: null,
      fallback: {
        title: "Нужна проверка данных",
        summary: "После исправления данных интерпретация будет сформирована автоматически.",
        qualityStatus: "BLOCKED",
        dataLimitationCodes: ["MISSING_COST_DATA"]
      }
    });

    expect(result.fallback?.qualityStatus).toBe("BLOCKED");
  });

  it("rejects a READY response without immutable content", () => {
    const invalid: unknown = { ...readyResponse(), content: null };

    const parsed = weeklyInsightSchema.safeParse(invalid);
    expect(parsed.success).toBe(false);
  });
});
