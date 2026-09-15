import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PlanDailyTarget } from "../api/contracts";
import { DailyPlanTable } from "./DailyPlanTable";

function dailyTarget(date: string, completed: boolean): PlanDailyTarget {
  return {
    date,
    completed,
    revenueBasisAmount: completed ? 100000 : 120000,
    revenueBasisProjected: !completed,
    accessory: {
      actualAmount: completed ? 5000 : null,
      actualSharePercent: completed ? 5 : null,
      targetAmount: completed ? 6300 : 9000,
      targetSharePercent: completed ? 6.3 : 7.5,
      cumulativeGapAmount: completed ? -1300 : null
    },
    service: {
      actualAmount: completed ? 5000 : null,
      actualSharePercent: completed ? 5 : null,
      targetAmount: completed ? 4200 : 6000,
      targetSharePercent: completed ? 4.2 : 5,
      cumulativeGapAmount: completed ? 800 : null
    }
  };
}

describe("DailyPlanTable", () => {
  it("puts the nearest future day first and explains its calculated values", () => {
    render(<DailyPlanTable targets={[
      dailyTarget("2026-08-17", false),
      dailyTarget("2026-08-15", true),
      dailyTarget("2026-08-16", false)
    ]} />);

    const nearestSection = screen.getByText("Ориентир на ближайший день").closest("section");
    expect(nearestSection).not.toBeNull();
    expect(within(nearestSection!).getByText(/16 авг/u)).toBeInTheDocument();
    expect(within(nearestSection!).getByText("Аксессуары")).toBeInTheDocument();
    expect(within(nearestSection!).getByText(/9.*000.*₽/u)).toBeInTheDocument();
    expect(within(nearestSection!).getByText("7,5%")).toBeInTheDocument();
    expect(within(nearestSection!).getByText("Услуги")).toBeInTheDocument();
    expect(within(nearestSection!).getByText(/6.*000.*₽/u)).toBeInTheDocument();
    expect(within(nearestSection!).getByText("5%")).toBeInTheDocument();
    expect(within(nearestSection!).getByText("Расчётная выручка дня")).toBeInTheDocument();
    expect(within(nearestSection!).getByText(/Расчёт сделан по результатам месяца/u))
      .toBeInTheDocument();
    expect(screen.queryByText("Прогноз выручки")).not.toBeInTheDocument();
  });

  it("shows the latest five completed days and keeps earlier history collapsed", () => {
    const completedTargets = Array.from({ length: 10 }, (_, index) =>
      dailyTarget(`2026-08-${String(index + 1).padStart(2, "0")}`, true));

    render(<DailyPlanTable targets={completedTargets} />);

    const history = screen.getByRole("heading", { name: "Последние завершённые дни" })
      .closest("section");
    expect(history).not.toBeNull();
    const recentDays = history!.querySelectorAll(":scope > .daily-plan-history__list > .daily-plan-day");
    expect(recentDays).toHaveLength(5);
    const firstRecentDay = recentDays.item(0) as HTMLElement;
    expect(firstRecentDay).not.toHaveAttribute("open");
    fireEvent.click(within(firstRecentDay).getByText(/6 авг/u));
    expect(firstRecentDay).toHaveAttribute("open");
    expect(within(history!).getAllByText("Отклонение с начала месяца")).toHaveLength(20);

    const earlier = within(history!).getByText("Показать более ранние дни").closest("details");
    expect(earlier).not.toBeNull();
    expect(earlier).not.toHaveAttribute("open");
    expect(earlier!.querySelectorAll(".daily-plan-day")).toHaveLength(5);
    fireEvent.click(within(earlier!).getByText("Показать более ранние дни"));
    expect(earlier).toHaveAttribute("open");
  });

  it("does not show a future orientation for a completed month", () => {
    render(<DailyPlanTable targets={[
      dailyTarget("2026-08-30", true),
      dailyTarget("2026-08-31", true)
    ]} />);

    expect(screen.queryByText("Ориентир на ближайший день")).not.toBeInTheDocument();
    expect(screen.queryByText("Оставшиеся ориентиры")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Последние завершённые дни" }))
      .toBeInTheDocument();
  });

  it("does not render when daily targets are empty", () => {
    const { container } = render(<DailyPlanTable targets={[]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("groups future dates after the nearest day into a collapsed range", () => {
    render(<DailyPlanTable targets={[
      dailyTarget("2026-08-15", true),
      dailyTarget("2026-08-16", false),
      dailyTarget("2026-08-17", false),
      dailyTarget("2026-08-18", false),
      dailyTarget("2026-08-19", false)
    ]} />);

    const futureDetails = screen.getByText("Оставшиеся ориентиры").closest("details");
    expect(futureDetails).not.toBeNull();
    expect(futureDetails).not.toHaveAttribute("open");
    expect(within(futureDetails!).getByText(/17 авг.*19 авг/u)).toBeInTheDocument();
    expect(within(futureDetails!).getByText("3 дн.")).toBeInTheDocument();
    expect(futureDetails!.querySelectorAll(".daily-plan-future-day")).toHaveLength(3);
  });
});
