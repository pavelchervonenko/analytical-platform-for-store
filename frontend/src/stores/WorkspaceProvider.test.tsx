import { render, screen } from "@testing-library/react";
import { useQuery } from "@tanstack/react-query";
import { MemoryRouter, useLocation } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WorkspaceProvider, useWorkspace } from "./WorkspaceProvider";

vi.mock("@tanstack/react-query", () => ({ useQuery: vi.fn() }));

const useQueryMock = vi.mocked(useQuery);

function Consumer() {
  const workspace = useWorkspace();
  const location = useLocation();
  return (
    <output data-testid="workspace">
      {JSON.stringify({
        month: workspace.month,
        periodMode: workspace.periodMode,
        periodEnd: workspace.periodEnd,
        planMonth: workspace.planMonth,
        planAsOfDate: workspace.planAsOfDate,
        search: location.search
      })}
    </output>
  );
}

function renderWorkspace(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <WorkspaceProvider><Consumer /></WorkspaceProvider>
    </MemoryRouter>
  );
}

describe("workspace plan period", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-14T09:00:00+03:00"));
    useQueryMock.mockImplementation(({ queryKey }) => {
      if ((queryKey as readonly unknown[]).length === 1) {
        return {
          data: [{ id: "store-1", name: "Магазин", timezone: "Europe/Moscow" }],
          isPending: false,
          isError: false
        } as ReturnType<typeof useQuery>;
      }
      return {
        data: { dataThroughDate: "2026-09-12" },
        isPending: false,
        isError: false
      } as ReturnType<typeof useQuery>;
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    useQueryMock.mockReset();
  });

  it("normalizes an invalid URL month instead of crashing", () => {
    renderWorkspace("/overview?store=store-1&month=2026-00");

    expect(screen.getByTestId("workspace")).toHaveTextContent('"month":"2026-09"');
    expect(screen.getByTestId("workspace")).toHaveTextContent("month=2026-09");
  });

  it("anchors a cross-month custom period to its ending month", () => {
    renderWorkspace("/overview?store=store-1&month=2026-08&range=CUSTOM&periodStart=2026-08-15&periodEnd=2026-09-13");

    expect(screen.getByTestId("workspace")).toHaveTextContent('"month":"2026-08"');
    expect(screen.getByTestId("workspace")).toHaveTextContent('"periodEnd":"2026-09-13"');
    expect(screen.getByTestId("workspace")).toHaveTextContent('"planMonth":"2026-09"');
    expect(screen.getByTestId("workspace")).toHaveTextContent('"planAsOfDate":"2026-09-12"');
  });

  it("uses the complete historical month for a partial historical range", () => {
    renderWorkspace("/overview?store=store-1&month=2026-07&range=CUSTOM&periodStart=2026-08-10&periodEnd=2026-08-16");

    expect(screen.getByTestId("workspace")).toHaveTextContent('"planMonth":"2026-08"');
    expect(screen.getByTestId("workspace")).toHaveTextContent('"planAsOfDate":"2026-08-31"');
  });
});
