import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { comparisonModeForPeriod, EmployeeAttachComparison } from "./EmployeeCardPage";

describe("employee card attach-rate comparison", () => {
  it("shows current and previous weekly rates, their change, and the store rate", () => {
    render(
      <EmployeeAttachComparison
        currentLabel="Текущая неделя"
        previousLabel="Прошлая неделя"
        currentRate={125}
        previousRate={75}
        storeRate={118.4}
        change={50}
      />
    );

    expect(screen.getByText("Текущая неделя")).toBeInTheDocument();
    expect(screen.getByText("Прошлая неделя")).toBeInTheDocument();
    expect(screen.getByText("125%")).toBeInTheDocument();
    expect(screen.getByText("75%")).toBeInTheDocument();
    expect(screen.getByText("+50 п.п.")).toHaveClass("text-positive");
    expect(screen.getByText("118,4%")).toBeInTheDocument();
  });

  it("does not invent a previous rate or a change when comparison data is absent", () => {
    render(
      <EmployeeAttachComparison
        currentLabel="Текущий период"
        previousLabel="Прошлый период"
        currentRate={40}
        previousRate={null}
        storeRate={35}
        change={null}
      />
    );

    expect(screen.getAllByText("—")).toHaveLength(2);
  });

  it("requests calendar-aligned comparison only for the weekly filter", () => {
    expect(comparisonModeForPeriod("WEEK")).toBe("PREVIOUS_WEEK");
    expect(comparisonModeForPeriod("MONTH")).toBe("PREVIOUS_PERIOD");
    expect(comparisonModeForPeriod("CUSTOM")).toBe("PREVIOUS_PERIOD");
  });
});
