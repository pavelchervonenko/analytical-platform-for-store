import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PlanDirection } from "../api/contracts";
import { DirectionCard, primaryPlanAction } from "./PlanPanel";

const revenue: PlanDirection = {
  code: "REVENUE",
  criterionType: "AMOUNT",
  actualAmount: 800000,
  targetAmount: 1000000,
  amountCompletionPercent: 80,
  currentDailyPace: 50000,
  expectedAmountToDate: 850000,
  paceGapAmount: -50000,
  projectedAmount: 950000,
  projectedAmountCompletionPercent: 95,
  remainingAmount: 200000,
  requiredPerRemainingDay: 25000,
  actualSharePercent: null,
  targetSharePercent: null,
  shareGapPercentagePoints: null,
  criterionCompletionPercent: 80,
  achieved: false,
  status: "AT_RISK"
};

const accessory: PlanDirection = {
  ...revenue,
  code: "ACCESSORY",
  criterionType: "SHARE",
  actualAmount: 48000,
  targetAmount: 64000,
  amountCompletionPercent: 75,
  projectedAmount: 96000,
  projectedAmountCompletionPercent: 75,
  remainingAmount: 16000,
  requiredPerRemainingDay: 2000,
  actualSharePercent: 6,
  targetSharePercent: 8,
  shareGapPercentagePoints: -2,
  criterionCompletionPercent: 75
};

describe("plan direction presentation", () => {
  it("labels the revenue card as progress toward the full monthly target", () => {
    render(<DirectionCard direction={revenue} />);

    const card = screen.getByText("Выручка").closest("article");
    expect(card).not.toBeNull();
    expect(within(card!).getByText("Выполнение месячной цели")).toBeInTheDocument();
    expect(within(card!).getByText(/Факт к месячной цели:/u)).toBeInTheDocument();
    expect(within(card!).getByText("Прогноз выручки на конец месяца")).toBeInTheDocument();
    expect(within(card!).getByText("Осталось до месячной цели")).toBeInTheDocument();
    expect(within(card!).getByText("До месячного плана в день")).toBeInTheDocument();
    expect(within(card!).queryByText("Нужно в день")).not.toBeInTheDocument();
    expect(primaryPlanAction(revenue)).toMatch(/выполнить месячный план/u);
  });

  it("labels a share card as recovery of the current accumulated gap", () => {
    render(<DirectionCard direction={accessory} />);

    const card = screen.getByText("Аксессуары").closest("article");
    expect(card).not.toBeNull();
    expect(within(card!).getByText("Доля на текущую дату")).toBeInTheDocument();
    expect(within(card!).getByText(/Факт к ориентиру на текущую выручку:/u)).toBeInTheDocument();
    expect(within(card!).getByText("Прогноз суммы на конец месяца")).toBeInTheDocument();
    expect(within(card!).getByText("Текущее отставание")).toBeInTheDocument();
    expect(within(card!).getByText("Закрыть отставание в день")).toBeInTheDocument();
    expect(within(card!).queryByText("Нужно в день")).not.toBeInTheDocument();
    expect(primaryPlanAction(accessory)).toMatch(/закрыть текущее отставание/u);
  });
});
