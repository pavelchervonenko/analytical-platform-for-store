import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import type { PlanDirection, PlanProgress } from "../api/contracts";
import { OverviewPlanPanel } from "./OverviewPlanPanel";

function direction(overrides: Partial<PlanDirection>): PlanDirection {
  return {
    code: "REVENUE",
    criterionType: "AMOUNT",
    actualAmount: 48_000_000,
    targetAmount: 55_000_000,
    amountCompletionPercent: 87.3,
    currentDailyPace: 1_600_000,
    expectedAmountToDate: 49_000_000,
    paceGapAmount: -1_000_000,
    projectedAmount: 57_000_000,
    projectedAmountCompletionPercent: 103.6,
    remainingAmount: 7_000_000,
    requiredPerRemainingDay: 1_000_000,
    actualSharePercent: null,
    targetSharePercent: null,
    shareGapPercentagePoints: null,
    criterionCompletionPercent: 87.3,
    achieved: false,
    status: "ON_TRACK",
    ...overrides
  };
}

function progress(remainingDays: number, directions: PlanDirection[]): PlanProgress {
  return {
    remainingDays,
    directions,
    dataQuality: {
      completeThroughAsOf: true,
      classificationComplete: true
    }
  } as PlanProgress;
}

function renderPanel(plan: PlanProgress | null | undefined) {
  return render(
    <MemoryRouter>
      <OverviewPlanPanel
        scope="STORE"
        plan={plan}
        isPending={plan === undefined}
        isError={false}
        error={null}
        onRetry={vi.fn()}
        planSearch="store=store-1&month=2026-09"
      />
    </MemoryRouter>
  );
}

describe("overview monthly plan panel", () => {
  it("links to the detailed plan without a direction counter", () => {
    renderPanel(progress(12, [direction({})]));

    expect(screen.getByRole("link", { name: "Открыть план" }))
      .toHaveAttribute("href", "/plan?store=store-1&month=2026-09");
    expect(screen.queryByText(/\d+ из \d+/u)).not.toBeInTheDocument();
  });

  it("uses manager-facing facts and deviations for an open month", () => {
    renderPanel(progress(12, [
      direction({}),
      direction({
        code: "ACCESSORY",
        criterionType: "SHARE",
        actualAmount: 2_400_000,
        targetAmount: 3_024_000,
        projectedAmount: 3_910_000,
        actualSharePercent: 5,
        targetSharePercent: 6.3,
        shareGapPercentagePoints: -1.3,
        criterionCompletionPercent: 79.4,
        status: "AT_RISK"
      }),
      direction({
        code: "SERVICE",
        criterionType: "SHARE",
        actualAmount: 2_160_000,
        targetAmount: 2_016_000,
        projectedAmount: 3_460_000,
        actualSharePercent: 4.5,
        targetSharePercent: 4.2,
        shareGapPercentagePoints: 0.3,
        criterionCompletionPercent: 107.1,
        status: "ACHIEVED"
      }),
      direction({
        code: "ADDITIONAL",
        criterionType: "SHARE",
        actualAmount: 4_560_000,
        targetAmount: 5_040_000,
        projectedAmount: 7_380_000,
        actualSharePercent: null,
        targetSharePercent: 10.5,
        shareGapPercentagePoints: null,
        criterionCompletionPercent: null,
        status: "NOT_AVAILABLE"
      })
    ]));

    const revenue = screen.getByText("Выручка").closest("article");
    const accessories = screen.getByText("Аксессуары").closest("article");
    const services = screen.getByText("Услуги").closest("article");
    const additional = screen.getByText("Доп. выручка").closest("article");

    expect(revenue).not.toBeNull();
    expect(within(revenue!).getByText("Сейчас 48 млн ₽ из 55 млн ₽, 87,3% плана")).toBeInTheDocument();
    expect(within(revenue!).getByText("Прогноз 57 млн ₽, 103,6% плана")).toBeInTheDocument();
    expect(within(revenue!).getByText("По графику")).toBeInTheDocument();

    expect(accessories).not.toBeNull();
    expect(within(accessories!).getByText("Сейчас 5% при цели 6,3%. Ниже цели на 1,3 п. п.")).toBeInTheDocument();
    expect(within(accessories!).getByText("Прогноз суммы 3,91 млн ₽")).toBeInTheDocument();
    expect(within(accessories!).getByText("Ниже цели")).toBeInTheDocument();

    expect(services).not.toBeNull();
    expect(within(services!).getByText("Сейчас 4,5% при цели 4,2%. Выше цели на 0,3 п. п.")).toBeInTheDocument();
    expect(within(services!).getByText("Прогноз суммы 3,46 млн ₽")).toBeInTheDocument();
    expect(within(services!).getByText("Цель достигнута")).toBeInTheDocument();

    expect(additional).not.toBeNull();
    expect(within(additional!).getByText("Доля пока недоступна")).toBeInTheDocument();
    expect(within(additional!).getByText("Недостаточно данных")).toBeInTheDocument();
    expect(screen.queryByText(/критерия/u)).not.toBeInTheDocument();
  });

  it("shows final results instead of forecasts for a closed month", () => {
    renderPanel(progress(0, [
      direction({
        actualAmount: 77_710_000,
        targetAmount: 55_000_000,
        projectedAmount: 77_710_000,
        criterionCompletionPercent: 141.3,
        achieved: true,
        status: "ACHIEVED"
      }),
      direction({
        code: "ACCESSORY",
        criterionType: "SHARE",
        actualSharePercent: 5,
        targetSharePercent: 6.3,
        shareGapPercentagePoints: -1.3,
        criterionCompletionPercent: 79.4,
        status: "MISSED"
      }),
      direction({
        code: "SERVICE",
        criterionType: "SHARE",
        actualSharePercent: 4.2,
        targetSharePercent: 4.2,
        shareGapPercentagePoints: 0,
        criterionCompletionPercent: 100,
        achieved: true,
        status: "ACHIEVED"
      })
    ]));

    const revenue = screen.getByText("Выручка").closest("article");
    expect(revenue).not.toBeNull();
    expect(within(revenue!).getByText("Итог 77,71 млн ₽ из 55 млн ₽, 141,3% плана")).toBeInTheDocument();
    expect(within(revenue!).getByText("Выше плана на 22,71 млн ₽")).toBeInTheDocument();
    expect(within(revenue!).getByRole("progressbar")).toHaveAttribute("value", "100");
    expect(screen.getByText("Итог 5% при цели 6,3%. Ниже цели на 1,3 п. п.")).toBeInTheDocument();
    expect(screen.getByText("Итог 4,2% при цели 4,2%. На уровне цели")).toBeInTheDocument();
    expect(screen.getAllByText("Выполнено")).toHaveLength(2);
    expect(screen.getByText("Не выполнено")).toBeInTheDocument();
    expect(screen.queryByText(/Прогноз/u)).not.toBeInTheDocument();
  });

  it("describes incomplete closed-month data as a pending final result", () => {
    const plan = progress(0, [direction({ status: "MISSED" })]);
    plan.dataQuality.completeThroughAsOf = false;
    renderPanel(plan);

    expect(screen.getByText("Итог обновится после загрузки всех данных месяца."))
      .toBeInTheDocument();
    expect(screen.queryByText(/Прогноз обновится/u)).not.toBeInTheDocument();
  });

  it("keeps the plan link available when the plan has not been configured", () => {
    renderPanel(null);

    expect(screen.getByText("План не задан")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Открыть план" })).toBeInTheDocument();
  });

  it("does not present a direction forecast as reliable while classification is incomplete", () => {
    const plan = progress(12, [
      direction({}),
      direction({
        code: "ACCESSORY",
        criterionType: "SHARE",
        actualSharePercent: 5,
        targetSharePercent: 6.3,
        shareGapPercentagePoints: -1.3,
        projectedAmount: 3_910_000,
        status: "AT_RISK"
      })
    ]);
    plan.dataQuality.classificationComplete = false;
    renderPanel(plan);

    expect(screen.getByText("Прогноз 57 млн ₽, 103,6% плана")).toBeInTheDocument();
    expect(screen.queryByText("Прогноз суммы 3,91 млн ₽")).not.toBeInTheDocument();
    expect(screen.getByText("Прогнозы по направлениям обновятся после распределения товаров."))
      .toBeInTheDocument();
  });

  it("hides a revenue forecast when the direction is unavailable", () => {
    renderPanel(progress(12, [direction({
      status: "NOT_AVAILABLE"
    })]));

    expect(screen.queryByText(/Прогноз 57 млн/u)).not.toBeInTheDocument();
    expect(screen.getByText("Недостаточно данных")).toBeInTheDocument();
  });
});
