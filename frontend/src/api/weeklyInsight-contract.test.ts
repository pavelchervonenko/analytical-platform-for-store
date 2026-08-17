import canonicalReadyContent from "../../../docs/schemas/examples/weekly-interpretation-content-v1-ready.json";
import { describe, expect, it } from "vitest";
import { weeklyInsightSchema } from "./weeklyInsightContract";

const employeeIds = [
  "30df06fb-71fe-4477-b6b9-bbc712b1ab25",
  "30df06fb-71fe-4477-b6b9-bbc712b1ab26"
];

const employeeIdsByRef = new Map([
  ["E01", employeeIds[0]],
  ["E02", employeeIds[1]]
]);

function publicReadyContent() {
  const evidenceCodes = new Map<string, string>();
  const content = JSON.parse(JSON.stringify(
    canonicalReadyContent,
    (field, value: unknown): unknown => {
      if (field === "evidenceRefs" && Array.isArray(value)) {
        return value.map((reference) => {
          if (typeof reference !== "string") return reference;
          const existing = evidenceCodes.get(reference);
          if (existing) return existing;
          const code = `EV${String(evidenceCodes.size + 1).padStart(3, "0")}`;
          evidenceCodes.set(reference, code);
          return code;
        });
      }
      if (field === "employeeRef" && typeof value === "string") {
        return employeeIdsByRef.get(value) ?? value;
      }
      if (
        Array.isArray(value)
        && (field === "employeeRefs" || field.endsWith("EmployeeRefs"))
      ) {
        return value.map((reference) => typeof reference === "string"
          ? employeeIdsByRef.get(reference) ?? reference
          : reference);
      }
      return value;
    }
  )) as typeof canonicalReadyContent;
  const evidence: Array<Record<string, unknown>> = Array.from(
    evidenceCodes.values()
  ).map((evidenceCode, index) => ({
    evidenceCode,
    label: `Подтверждённый показатель ${index + 1}`,
    formattedValue: null,
    previousFormattedValue: null,
    absoluteDeltaFormatted: null,
    relativeDeltaFormatted: null,
    comparisonText: null,
    unit: null,
    sufficiency: null,
    scope: "STORE",
    employeeId: null,
    displayName: null,
    categoryLabel: null,
    available: false
  }));
  return { content, evidence };
}

function readyResponse() {
  const { content, evidence } = publicReadyContent();
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
      dataLimitations: content.dataLimitations,
      evidence
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

  it("accepts backend-formatted evidence without internal references", () => {
    const response = readyResponse();
    const firstEvidence = response.content.evidence[0];
    if (!firstEvidence || typeof firstEvidence.evidenceCode !== "string") {
      throw new Error("Canonical projection must cite evidence");
    }
    response.content.evidence[0] = {
      evidenceCode: firstEvidence.evidenceCode,
      label: "Выручка",
      formattedValue: "1 200 000 ₽",
      previousFormattedValue: "1 000 000 ₽",
      absoluteDeltaFormatted: "+200 000 ₽",
      relativeDeltaFormatted: "+20%",
      comparisonText: "Было 1 000 000 ₽ · изменение +200 000 ₽ (+20%)",
      unit: "MONEY",
      sufficiency: "SUFFICIENT",
      scope: "STORE",
      employeeId: null,
      displayName: null,
      categoryLabel: null,
      available: true
    };

    const result = weeklyInsightSchema.parse(response);

    expect(result.content?.evidence).toContainEqual(
      expect.objectContaining({
        evidenceCode: "EV001",
        label: "Выручка",
        formattedValue: "1 200 000 ₽",
        comparisonText: expect.stringContaining("изменение"),
        available: true
      })
    );
  });

  it("rejects a technical evidence code in the public bundle", () => {
    const response = readyResponse();
    const firstEvidence = response.content.evidence[0];
    if (!firstEvidence) throw new Error("Canonical projection must cite evidence");
    firstEvidence.evidenceCode = "EMP:E01.NET_REVENUE.CURRENT";

    expect(weeklyInsightSchema.safeParse(response).success).toBe(false);
  });

  it("rejects a technical reference nested in public content", () => {
    const response = readyResponse();
    response.content.store.headline.evidenceRefs = [
      "STORE.NET_REVENUE.CURRENT"
    ];

    expect(weeklyInsightSchema.safeParse(response).success).toBe(false);
  });

  it("rejects cited evidence missing from the public bundle", () => {
    const response = readyResponse();
    response.content.evidence.shift();

    expect(weeklyInsightSchema.safeParse(response).success).toBe(false);
  });

  it("rejects an internal employee pseudonym in public content", () => {
    const response = readyResponse();
    const reference = response.content.store.headline.evidenceRefs[0];
    if (!reference) throw new Error("Canonical projection must cite evidence");
    response.content.teamInsights.mostImproved = [{
      employeeRef: "E01",
      kind: "OBSERVATION",
      summary: "Есть положительная динамика.",
      evidenceRefs: [reference]
    }];

    expect(weeklyInsightSchema.safeParse(response).success).toBe(false);
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
