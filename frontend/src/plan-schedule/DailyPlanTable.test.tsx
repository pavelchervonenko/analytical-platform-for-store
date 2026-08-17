import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PlanDailyTarget } from "../api/contracts";
import { DailyPlanTable } from "./DailyPlanTable";

const completed: PlanDailyTarget = {
  date: "2026-08-15",
  completed: true,
  revenueBasisAmount: 100000,
  revenueBasisProjected: false,
  accessory: {
    actualAmount: 5000,
    actualSharePercent: 5,
    targetAmount: 6300,
    targetSharePercent: 6.3,
    cumulativeGapAmount: -1300
  },
  service: {
    actualAmount: 5000,
    actualSharePercent: 5,
    targetAmount: 4200,
    targetSharePercent: 4.2,
    cumulativeGapAmount: 800
  }
};

const future: PlanDailyTarget = {
  date: "2026-08-16",
  completed: false,
  revenueBasisAmount: 120000,
  revenueBasisProjected: true,
  accessory: {
    actualAmount: null,
    actualSharePercent: null,
    targetAmount: 9000,
    targetSharePercent: 7.5,
    cumulativeGapAmount: null
  },
  service: {
    actualAmount: null,
    actualSharePercent: null,
    targetAmount: 6000,
    targetSharePercent: 5,
    cumulativeGapAmount: null
  }
};

describe("DailyPlanTable", () => {
  it("shows completed facts and recalculated future percentages separately", () => {
    render(<DailyPlanTable targets={[completed, future]} />);

    expect(screen.getByRole("heading", { name: "Аксессуары и услуги" }))
      .toBeInTheDocument();
    const completedRow = screen.getByRole("row", { name: /15 авг/u });
    expect(within(completedRow).getByText("завершён")).toBeInTheDocument();
    expect(within(completedRow).getAllByText("5%")).toHaveLength(2);
    expect(within(completedRow).getByText(/итог -1.*300.*₽/u))
      .toBeInTheDocument();

    const futureRow = screen.getByRole("row", { name: /16 авг/u });
    expect(within(futureRow).getByText("прогноз")).toBeInTheDocument();
    expect(within(futureRow).getByText("7,5%")).toBeInTheDocument();
    expect(within(futureRow).getByText("5%")).toBeInTheDocument();
    expect(within(futureRow).getAllByText("с учётом темпа")).toHaveLength(2);
  });

  it("does not render an empty schedule", () => {
    const { container } = render(<DailyPlanTable targets={[]} />);

    expect(container).toBeEmptyDOMElement();
  });
});
