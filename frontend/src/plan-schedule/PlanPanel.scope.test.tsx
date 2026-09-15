import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PlanPanel } from "./PlanPanel";

const { useQueryMock } = vi.hoisted(() => ({ useQueryMock: vi.fn() }));

vi.mock("@tanstack/react-query", () => ({ useQuery: useQueryMock }));

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({
    selectedStore: { id: "store-1", name: "Магазин", timezone: "Europe/Moscow" },
    month: "2026-09",
    asOfDate: "2026-09-13"
  })
}));

function queryResult() {
  return {
    data: null,
    error: null,
    isError: false,
    isPending: false,
    refetch: vi.fn()
  };
}

describe("plan scope", () => {
  beforeEach(() => useQueryMock.mockReset().mockReturnValue(queryResult()));

  it("uses sellers by default and switches the canonical query to the whole store", async () => {
    render(<MemoryRouter initialEntries={["/plan?store=store-1&month=2026-09"]}><PlanPanel /></MemoryRouter>);

    expect(screen.getByRole("button", { name: "Только продавцы" })).toHaveAttribute("aria-pressed", "true");
    expect(useQueryMock).toHaveBeenLastCalledWith(expect.objectContaining({
      queryKey: ["stores", "store-1", "plan-progress", "2026-09", "2026-09-13", "SELLERS"]
    }));

    fireEvent.click(screen.getByRole("button", { name: "Весь магазин" }));

    await waitFor(() => expect(useQueryMock).toHaveBeenLastCalledWith(expect.objectContaining({
      queryKey: ["stores", "store-1", "plan-progress", "2026-09", "2026-09-13", "STORE"]
    })));
    expect(screen.getByRole("button", { name: "Весь магазин" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("link", { name: "Настроить план" }))
      .toHaveAttribute("href", "/plan/settings?store=store-1&month=2026-09&planScope=STORE");
  });

  it("falls back to sellers for an unknown URL scope", () => {
    render(<MemoryRouter initialEntries={["/plan?planScope=UNKNOWN"]}><PlanPanel /></MemoryRouter>);

    expect(screen.getByRole("button", { name: "Только продавцы" })).toHaveAttribute("aria-pressed", "true");
    expect(useQueryMock).toHaveBeenLastCalledWith(expect.objectContaining({
      queryKey: expect.arrayContaining(["SELLERS"])
    }));
  });
});
