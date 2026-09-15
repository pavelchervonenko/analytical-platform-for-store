import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClientError } from "../api/client";
import type { OverviewMetrics } from "../api/contracts";
import { OverviewPage } from "./OverviewPage";

const { useQueryMock } = vi.hoisted(() => ({ useQueryMock: vi.fn() }));

vi.mock("@tanstack/react-query", () => ({ useQuery: useQueryMock }));

vi.mock("../auth/AuthProvider", () => ({
  useAuth: () => ({ user: { role: "MANAGER", features: ["PLAN"] } })
}));

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({
    selectedStore: { id: "store-1", name: "Магазин", timezone: "Europe/Moscow" },
    month: "2026-09",
    periodMode: "MONTH",
    periodStart: "2026-09-01",
    periodEnd: "2026-09-08",
    periodLabel: "сентябрь 2026 г.",
    asOfDate: "2026-09-08",
    planMonth: "2026-09",
    planAsOfDate: "2026-09-08"
  })
}));

const metrics: OverviewMetrics = {
  storeId: "store-1",
  periodStart: "2026-09-01",
  periodEnd: "2026-09-08",
  scope: "SELLERS",
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
};

function queryResult(data: unknown, isError = false) {
  return {
    data,
    error: isError ? new ApiClientError("Нет соединения", { status: 0, code: "NETWORK_ERROR" }) : null,
    isError,
    isPending: false,
    refetch: vi.fn()
  };
}

describe("overview query degradation", () => {
  beforeEach(() => {
    useQueryMock.mockImplementation(({ queryKey }: { queryKey: readonly unknown[] }) => {
      if (queryKey.includes("overview-metrics")) return queryResult(metrics, true);
      return queryResult(undefined, true);
    });
  });

  it("keeps the last successful dashboard when a background refresh fails", () => {
    render(<MemoryRouter><OverviewPage /></MemoryRouter>);

    expect(screen.getByText("Чистая выручка")).toBeInTheDocument();
    expect(screen.getByText("100 000 ₽")).toBeInTheDocument();
    expect(screen.getByText("Показаны последние доступные данные.", { exact: false })).toBeInTheDocument();
  });
});
