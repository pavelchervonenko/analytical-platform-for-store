import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
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
    periodMode: "MONTH",
    periodStart: "2026-08-01",
    periodEnd: "2026-08-31",
    periodLabel: "август 2026 г.",
    asOfDate: "2026-08-15",
    planMonth: "2026-08",
    planAsOfDate: "2026-08-15"
  })
}));

vi.mock("../auth/AuthProvider", () => ({
  useAuth: () => ({ user: { role: "ADMIN" } })
}));

vi.mock("./WeeklyReviewView", () => ({
  WeeklyReviewView: ({
    storeId,
    qualityHref
  }: {
    storeId: string;
    qualityHref: string | null;
  }) => (
    <div data-quality-href={qualityHref ?? ""} data-testid="weekly-review-view">
      {storeId}
    </div>
  )
}));

describe("AI insight placement", () => {
  it("does not render the weekly AI interpretation on the overview", () => {
    render(
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    );

    expect(screen.queryByTestId("weekly-review-view")).not.toBeInTheDocument();
  });

  it("keeps the weekly AI interpretation in its dedicated section", () => {
    render(<InsightsPreviewPage />);

    expect(screen.getByTestId("weekly-review-view"))
      .toHaveTextContent("store-1");
    expect(screen.getByTestId("weekly-review-view"))
      .toHaveAttribute("data-quality-href", "/quality?store=store-1");
  });
});
