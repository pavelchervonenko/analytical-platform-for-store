import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { OverviewPage } from "../dashboard/OverviewPage";
import { InsightsPreviewPage } from "./InsightsPreviewPage";

vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({
    data: undefined,
    error: null,
    isError: false,
    isFetching: false,
    isPending: false,
    refetch: vi.fn()
  })
}));

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({
    selectedStore: { id: "store-1", name: "Магазин" },
    month: "2026-08",
    periodStart: "2026-08-01",
    periodEnd: "2026-08-31",
    asOfDate: "2026-08-15"
  })
}));

vi.mock("./WeeklyInsightPanel", () => ({
  WeeklyInsightPanel: ({ storeId }: { storeId: string }) => (
    <div data-testid="weekly-insight-panel">{storeId}</div>
  )
}));

describe("AI insight placement", () => {
  it("does not render the weekly AI interpretation on the overview", () => {
    render(<OverviewPage />);

    expect(screen.queryByTestId("weekly-insight-panel")).not.toBeInTheDocument();
  });

  it("keeps the weekly AI interpretation in its dedicated section", () => {
    render(<InsightsPreviewPage />);

    expect(screen.getByTestId("weekly-insight-panel")).toHaveTextContent("store-1");
  });
});
