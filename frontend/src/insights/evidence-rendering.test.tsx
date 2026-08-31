import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { WeeklyInsightView } from "./WeeklyInsightView";

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
        employees: Array.from({ length: 5 }, (_, index) => ({
          employeeId: `00000000-0000-4000-8000-00000000000${index + 1}`,
          displayName: `Сотрудник ${index + 1}`,
          analysisStatus: "INSUFFICIENT",
          insight: {
            analysisStatus: "INSUFFICIENT",
            headline: {
              text: "Сотрудник обслужил определенное количество клиентов.",
              evidenceRefs: ["EV004"]
            },
            workloadContext: {
              text: "Нагрузка сотрудников описана одинаково.",
              evidenceRefs: ["EV004"]
            },
            performanceSummary: null,
            dynamicsSummary: null,
            categoryPerformance: null,
            additionalSalesPerformance: null,
            strength: null,
            attentionArea: null,
            primaryRisk: null,
            recommendedActions: [],
            dataLimitations: []
          }
        })),
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
        }, {
          evidenceCode: "EV004",
          label: "Завершенные продажи",
          formattedValue: "7",
          previousFormattedValue: "5",
          absoluteDeltaFormatted: "+2",
          relativeDeltaFormatted: "+40%",
          comparisonText: "Было 5, изменение +2 (+40%)",
          unit: "COUNT",
          sufficiency: "LIMITED",
          scope: "EMPLOYEE",
          employeeId: "00000000-0000-4000-8000-000000000001",
          displayName: "Сотрудник 1",
          categoryLabel: null,
          available: true
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
    render(<WeeklyInsightView storeId="store-1" />);

    expect(screen.getAllByText("Выручка")).toHaveLength(2);
    expect(screen.getAllByText("1 200 000 ₽")).toHaveLength(2);
    expect(screen.getByText("Изменение +20%")).toBeInTheDocument();
    expect(screen.getAllByText(
      "Было 1 000 000 ₽, изменение +200 000 ₽ (+20%)"
    )).toHaveLength(1);
    expect(screen.getByText("Сотрудники в сравнении")).toBeInTheDocument();
    expect(screen.getByText("Качество классификации")).toBeInTheDocument();
    expect(screen.getByText("Значение недоступно")).toBeInTheDocument();
    expect(screen.getByText("Итоги недели")).toBeInTheDocument();
    expect(screen.getByText("Что требует внимания")).toBeInTheDocument();
    expect(screen.getByText(
      "Возможная причина — ее нужно проверить по дополнительным данным."
    )).toBeInTheDocument();
    expect(screen.getByText("Основание гипотезы")).toBeInTheDocument();
    expect(
      screen.getAllByText("Почему такой вывод").length
    ).toBeGreaterThan(0);
    expect(
      screen.getAllByText("Проверить данные — ограничены").length
    ).toBeGreaterThan(0);
    expect(screen.queryByText("Гипотеза")).not.toBeInTheDocument();
    expect(screen.queryByText("Подробности")).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("·");
  });

  it("replaces repeated employee filler and combines its evidence", () => {
    render(<WeeklyInsightView storeId="store-1" />);

    expect(screen.queryByText(
      "Сотрудник обслужил определенное количество клиентов."
    )).not.toBeInTheDocument();
    expect(screen.queryByText(
      "Нагрузка сотрудников описана одинаково."
    )).not.toBeInTheDocument();
    expect(
      screen.getAllByText("Проверить данные — ограничены")
    ).toHaveLength(5);
    expect(document.querySelectorAll(
      ".insight-employee__notice .insight-evidence"
    )).toHaveLength(5);
    expect(document.querySelectorAll(
      ".insight-employee__narratives"
    )).toHaveLength(0);
  });

  it("keeps the mobile employee preview short and groups the remaining employees", () => {
    render(<WeeklyInsightView storeId="store-1" />);

    const primaryList = document.querySelector(
      ".insight-employees > .insight-employees__list"
    );

    expect(primaryList?.querySelectorAll(".insight-employee")).toHaveLength(5);
    expect(primaryList?.querySelectorAll(
      ".insight-employees__item--mobile-hidden"
    )).toHaveLength(2);
    const moreButton = screen.getByText("Еще 2 сотрудника")
      .closest("button") as HTMLButtonElement;
    expect(moreButton).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(moreButton);
    expect(primaryList?.querySelectorAll(
      ".insight-employees__item--mobile-hidden"
    )).toHaveLength(0);
    const collapseButton = screen.getByText("Скрыть остальных")
      .closest("button");
    expect(collapseButton).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText(
      "Для 5 из 5 сотрудников пока недостаточно данных для надежного персонального разбора."
    )).toBeInTheDocument();
    expect(screen.getByText("5 сотрудников")).toBeInTheDocument();
    expect(document.querySelectorAll(
      ".insight-employee__status"
    )).toHaveLength(0);
  });
});
