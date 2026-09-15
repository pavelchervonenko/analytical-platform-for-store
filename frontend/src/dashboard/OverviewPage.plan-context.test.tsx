import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { OverviewMetrics } from "../api/contracts";
import { OverviewPage } from "./OverviewPage";

const { auth, useQueryMock } = vi.hoisted(() => ({
  auth: { features: ["PLAN"] as string[] },
  useQueryMock: vi.fn()
}));

vi.mock("@tanstack/react-query", () => ({ useQuery: useQueryMock }));

vi.mock("../auth/AuthProvider", () => ({
  useAuth: () => ({ user: { role: "MANAGER", features: auth.features } })
}));

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({
    selectedStore: { id: "store-1", name: "Магазин", timezone: "Europe/Moscow" },
    month: "2026-09",
    periodMode: "WEEK",
    periodStart: "2026-09-28",
    periodEnd: "2026-10-04",
    periodLabel: "28 сент. 2026 г. — 4 окт. 2026 г.",
    asOfDate: "2026-09-30",
    planMonth: "2026-10",
    planAsOfDate: "2026-10-03"
  })
}));

const metrics = {
  storeId: "store-1",
  periodStart: "2026-09-28",
  periodEnd: "2026-10-04",
  scope: "STORE",
  formulaVersion: "overview-v1",
  netRevenue: 100_000,
  netQuantity: 10,
  costAmount: 60_000,
  grossProfit: 40_000,
  marginPercent: 40,
  additional: { netRevenue: 15_000, netQuantity: 4, sharePercent: 15 },
  accessory: { netRevenue: 10_000, netQuantity: 3, sharePercent: 10 },
  service: { netRevenue: 5_000, netQuantity: 1, sharePercent: 5 },
  salesGroups: [],
  dataQuality: {
    completeCostData: true,
    includedItemCount: 10,
    unmappedItemCount: 0,
    missingCostItemCount: 0,
    unexpectedZeroCostItemCount: 0,
    periodOpenConsistencyIssueCount: 0,
    storeOpenQualityIssueCount: 0,
    reconciliationPassed: true
  }
} satisfies OverviewMetrics;

function queryResult(data: unknown) {
  return { data, error: null, isError: false, isPending: false, refetch: vi.fn() };
}

describe("overview plan context", () => {
  beforeEach(() => {
    auth.features = ["PLAN"];
    useQueryMock.mockReset().mockImplementation(({ queryKey }: { queryKey: readonly unknown[] }) => {
      if (queryKey.includes("overview-metrics")) return queryResult(metrics);
      if (queryKey.includes("plan-progress")) return queryResult(null);
      return queryResult(undefined);
    });
  });

  it("does not request or render plan progress without plan access", () => {
    auth.features = ["SHIFTS"];
    render(<MemoryRouter initialEntries={["/overview?overviewScope=STORE"]}><OverviewPage /></MemoryRouter>);

    expect(useQueryMock).toHaveBeenCalledWith(expect.objectContaining({
      queryKey: ["stores", "store-1", "plan-progress", "2026-10", "2026-10-03", "STORE"],
      enabled: false
    }));
    expect(screen.queryByRole("heading", { name: "План месяца — весь магазин" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Открыть план" })).not.toBeInTheDocument();
  });

  it("uses the end month of a cross-boundary range and the overview scope", () => {
    render(<MemoryRouter initialEntries={["/overview?overviewScope=STORE"]}><OverviewPage /></MemoryRouter>);

    expect(useQueryMock).toHaveBeenCalledWith(expect.objectContaining({
      queryKey: ["stores", "store-1", "plan-progress", "2026-10", "2026-10-03", "STORE"]
    }));
    expect(screen.getByRole("heading", { name: "План месяца — весь магазин" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Открыть план" }))
      .toHaveAttribute("href", "/plan?store=store-1&month=2026-10");
  });
});
