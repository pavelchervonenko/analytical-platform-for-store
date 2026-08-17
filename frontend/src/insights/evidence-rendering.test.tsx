import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { WeeklyInsightPanel } from "./WeeklyInsightPanel";

vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({
    data: {
      period: {
        periodStart: "2026-08-03",
        periodEnd: "2026-08-09",
        timezone: "Europe/Moscow"
      },
      state: "READY",
      reasonCode: "READY",
      message: "Интерпретация готова.",
      statusUpdatedAt: "2026-08-10T05:00:00Z",
      nextRefreshAt: null,
      interpretationId: "30df06fb-71fe-4477-b6b9-bbc712b1ab20",
      revision: 1,
      publishedAt: "2026-08-10T05:00:00Z",
      sourceDataUpdatedAt: "2026-08-10T04:30:00Z",
      revisionState: "CURRENT",
      content: {
        store: {
          headline: {
            text: "Выручка выросла по сравнению с прошлой неделей.",
            evidenceRefs: ["EV001"]
          },
          resultSummary: null,
          dynamicsSummary: null,
          categoryPerformance: null,
          additionalSalesPerformance: null,
          planOutlook: null,
          strength: null,
          attentionArea: {
            kind: "HYPOTHESIS",
            theme: "REVENUE_DYNAMICS",
            candidateRef: "C001",
            title: "Возможная причина динамики",
            summary: "На результат могла повлиять структура потока.",
            evidenceRefs: ["EV001"]
          },
          primaryRisk: null,
          recommendedActions: []
        },
        teamInsights: {
          summary: {
            text: "Команда работала стабильно.",
            evidenceRefs: ["EV002"]
          },
          highlights: [],
          competencyLeaders: [],
          mostImproved: [],
          learningOpportunities: []
        },
        employees: [],
        dataLimitations: [{
          code: "CLASSIFICATION_LIMITED",
          scope: "STORE",
          employeeRef: null,
          categoryCode: null,
          impact: "REDUCED_CONFIDENCE",
          affectedSections: ["CATEGORY_PERFORMANCE"],
          summary: "Часть категорий требует проверки.",
          evidenceRefs: ["EV003"]
        }],
        evidence: [{
          evidenceCode: "EV001",
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
        }, {
          evidenceCode: "EV002",
          label: "Сотрудники в сравнении",
          formattedValue: "3",
          previousFormattedValue: null,
          absoluteDeltaFormatted: null,
          relativeDeltaFormatted: null,
          comparisonText: null,
          unit: "COUNT",
          sufficiency: "SUFFICIENT",
          scope: "TEAM",
          employeeId: null,
          displayName: null,
          categoryLabel: null,
          available: true
        }, {
          evidenceCode: "EV003",
          label: "Качество классификации",
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
        }]
      },
      fallback: null
    },
    error: null,
    isError: false,
    isFetching: false,
    isPending: false,
    refetch: vi.fn()
  })
}));

describe("weekly insight evidence rendering", () => {
  it("shows backend-formatted evidence next to cited conclusions", () => {
    render(<WeeklyInsightPanel storeId="store-1" />);

    expect(screen.getAllByText("Выручка")).toHaveLength(2);
    expect(screen.getAllByText("1 200 000 ₽")).toHaveLength(2);
    expect(screen.getAllByText(
      "Было 1 000 000 ₽ · изменение +200 000 ₽ (+20%)"
    )).toHaveLength(2);
    expect(screen.getByText("Сотрудники в сравнении")).toBeInTheDocument();
    expect(screen.getByText("Качество классификации")).toBeInTheDocument();
    expect(screen.getByText("Значение недоступно")).toBeInTheDocument();
    expect(screen.getByText("Гипотеза")).toBeInTheDocument();
    expect(screen.getByText(
      "Возможная причина — её нужно проверить по дополнительным данным."
    )).toBeInTheDocument();
    expect(screen.getByText("Основание гипотезы")).toBeInTheDocument();
    expect(screen.getAllByText("Подтверждено данными").length).toBeGreaterThan(0);
    expect(screen.getByText("Данные с ограничениями")).toBeInTheDocument();
  });
});
