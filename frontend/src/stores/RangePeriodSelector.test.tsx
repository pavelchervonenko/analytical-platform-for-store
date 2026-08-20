import { fireEvent, render } from "@testing-library/react";
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

  it("offers the approved quick periods without yearly shortcuts", () => {
    const { container } = render(<RangePeriodSelector analyticsEnabled />);

    clickButton(container, "Выбрать период");

    expect(findButton(container, "Сегодня")).toBeDefined();
    expect(findButton(container, "Прошлый месяц")).toBeDefined();
    expect(findButton(container, "Этот год")).toBeUndefined();
    expect(findButton(container, "Прошлый год")).toBeUndefined();
  });

  it("applies rolling days through the latest covered date", () => {
    const { container } = render(<RangePeriodSelector analyticsEnabled />);

    clickButton(container, "Выбрать период");
    clickButton(container, "7 дней");
    clickButton(container, "Применить");

    expect(mocks.selectAnalyticsPeriod).toHaveBeenCalledWith({
      mode: "CUSTOM",
      start: "2026-08-12",
      end: "2026-08-18"
    });
  });

  it("selects and applies an inclusive custom range", () => {
    const { container } = render(<RangePeriodSelector analyticsEnabled />);

    clickButton(container, "Выбрать период");
    fireEvent.click(container.querySelectorAll<HTMLButtonElement>('[data-date="2026-08-05"]')[0]!);
    fireEvent.click(container.querySelectorAll<HTMLButtonElement>('[data-date="2026-08-10"]')[0]!);
    clickButton(container, "Применить");

    expect(mocks.selectAnalyticsPeriod).toHaveBeenCalledWith({
      mode: "CUSTOM",
      start: "2026-08-05",
      end: "2026-08-10"
    });
  });
});

function findButton(container: HTMLElement, label: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll<HTMLButtonElement>("button"))
    .find((button) => button.getAttribute("aria-label") === label || button.textContent?.trim() === label);
}

function clickButton(container: HTMLElement, label: string): void {
  const button = findButton(container, label);
  expect(button, `button ${label}`).toBeDefined();
  fireEvent.click(button!);
}
