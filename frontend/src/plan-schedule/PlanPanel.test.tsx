import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PlanDirection } from "../api/contracts";
import {
  directionStatusLabel,
  getPlanSummary,
  primaryPlanAction,
  RevenuePlanBlock,
  RevenueStructureBlock
} from "./PlanPanel";

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

const service: PlanDirection = {
  ...accessory,
  code: "SERVICE",
  actualSharePercent: 4.5,
  targetSharePercent: 5,
  shareGapPercentagePoints: -0.5
};

const additional: PlanDirection = {
  ...accessory,
  code: "ADDITIONAL",
  actualSharePercent: 10.5,
  targetSharePercent: 13,
  shareGapPercentagePoints: -2.5
};

describe("plan overview presentation", () => {
  it("shows revenue as one focused block with the manager's key values", () => {
    render(<RevenuePlanBlock direction={revenue} monthClosed={false} />);

    const block = screen.getByRole("region", { name: "Месячная цель" });
    expect(within(block).getByText("Темп ниже плана")).toBeInTheDocument();
    expect(within(block).getByText(/800\s000\s₽/u)).toBeInTheDocument();
    expect(within(block).getByText("Прогноз на конец месяца")).toBeInTheDocument();
    expect(within(block).getByText("Нужно в день до конца месяца")).toBeInTheDocument();
    expect(within(block).getByRole("progressbar")).toHaveAccessibleName("Выполнение плана выручки: 80%");
  });

  it("groups the three independent share goals and keeps money details secondary", () => {
    render(<RevenueStructureBlock directions={[revenue, accessory, service, additional]} monthClosed={false} />);

    const block = screen.getByRole("region", { name: "Доли направлений" });
    expect(within(block).getByText("Аксессуары")).toBeInTheDocument();
    expect(within(block).getByText("Услуги")).toBeInTheDocument();
    expect(within(block).getByText("Доп. выручка")).toBeInTheDocument();
    expect(within(block).getAllByText("Ниже цели")).toHaveLength(3);
    expect(within(block).getByText("−2 п. п.")).toBeInTheDocument();
    expect(within(block).getAllByText("Фактическая сумма")).toHaveLength(3);
    expect(block.querySelectorAll("details")).toHaveLength(3);
  });

  it("does not call an open month completed", () => {
    const achieved = { ...accessory, achieved: true, status: "ACHIEVED" };

    expect(getPlanSummary([achieved], true, 12)).toEqual({
      label: "Все цели достигнуты на текущую дату",
      description: "Продолжайте следить за темпом до конца месяца.",
      tone: "success"
    });
    expect(directionStatusLabel(achieved, false)).toBe("Цель достигнута на текущую дату");
  });

  it("uses final wording only after the month is closed", () => {
    const achieved = { ...accessory, achieved: true, status: "ACHIEVED" };

    expect(getPlanSummary([achieved], true, 0).label).toBe("План выполнен");
    expect(directionStatusLabel(achieved, true)).toBe("Цель выполнена");
  });

  it("replaces daily prescriptions with final gaps after the month is closed", () => {
    render(
      <>
        <RevenuePlanBlock direction={{ ...revenue, status: "MISSED", requiredPerRemainingDay: null }} monthClosed />
        <RevenueStructureBlock
          directions={[
            revenue,
            { ...accessory, status: "MISSED", requiredPerRemainingDay: null },
            service,
            additional
          ]}
          monthClosed
        />
      </>
    );

    expect(screen.getByText("Итоговый недобор")).toBeInTheDocument();
    expect(screen.getAllByText("Итоговое отставание")).toHaveLength(3);
    expect(screen.queryByText("Прогноз суммы на конец месяца")).not.toBeInTheDocument();
    expect(screen.queryByText("Нужно в день до конца месяца")).not.toBeInTheDocument();
    expect(screen.queryByText("Для сокращения отставания в день")).not.toBeInTheDocument();
  });

  it("does not prescribe a daily pace after a missed month is closed", () => {
    expect(primaryPlanAction({ ...revenue, status: "MISSED", requiredPerRemainingDay: null }))
      .toBe("Выручка: итог месяца, цель не выполнена.");
  });

  it("does not ask the manager to inspect technical data", () => {
    expect(primaryPlanAction({ ...accessory, status: "NOT_AVAILABLE", requiredPerRemainingDay: null }))
      .toBe("Аксессуары: расчёт появится после обновления данных.");
  });
});
