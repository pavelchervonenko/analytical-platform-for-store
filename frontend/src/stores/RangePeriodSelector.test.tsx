import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RangePeriodSelector } from "./RangePeriodSelector";

const mocks = vi.hoisted(() => ({
  selectAnalyticsPeriod: vi.fn()
}));

vi.mock("./WorkspaceProvider", () => ({
  useWorkspace: () => ({
    selectedStore: { id: "store-1", name: "МАГАЗИН" },
    month: "2026-08",
    periodMode: "CUSTOM",
    periodStart: "2026-08-01",
    periodEnd: "2026-08-18",
    periodLabel: "1 авг. 2026 г. — 18 авг. 2026 г.",
    currentMonth: "2026-08",
    today: "2026-08-20",
    dataThroughDate: "2026-08-18",
    completedThroughDate: "2026-08-18",
    selectAnalyticsPeriod: mocks.selectAnalyticsPeriod
  })
}));

describe("range period selector", () => {
  beforeEach(() => mocks.selectAnalyticsPeriod.mockReset());

  it("offers the approved quick periods without yearly shortcuts", async () => {
    const user = userEvent.setup();
    render(<RangePeriodSelector analyticsEnabled />);

    await user.click(screen.getByRole("button", { name: "Выбрать период" }));

    expect(screen.getByRole("button", { name: "Сегодня" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Прошлый месяц" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Этот год" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Прошлый год" })).not.toBeInTheDocument();
  });

  it("applies rolling days through the latest covered date", async () => {
    const user = userEvent.setup();
    render(<RangePeriodSelector analyticsEnabled />);

    await user.click(screen.getByRole("button", { name: "Выбрать период" }));
    await user.click(screen.getByRole("button", { name: "7 дней" }));
    await user.click(screen.getByRole("button", { name: "Применить" }));

    expect(mocks.selectAnalyticsPeriod).toHaveBeenCalledWith({
      mode: "CUSTOM",
      start: "2026-08-12",
      end: "2026-08-18"
    });
  });

  it("selects and applies an inclusive custom range", async () => {
    const user = userEvent.setup();
    render(<RangePeriodSelector analyticsEnabled />);

    await user.click(screen.getByRole("button", { name: "Выбрать период" }));
    await user.click(screen.getAllByRole("button", { name: /5 августа 2026/u })[0]!);
    await user.click(screen.getAllByRole("button", { name: /10 августа 2026/u })[0]!);
    await user.click(screen.getByRole("button", { name: "Применить" }));

    expect(mocks.selectAnalyticsPeriod).toHaveBeenCalledWith({
      mode: "CUSTOM",
      start: "2026-08-05",
      end: "2026-08-10"
    });
  });
});
