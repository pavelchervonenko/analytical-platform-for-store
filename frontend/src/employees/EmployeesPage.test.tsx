import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EmployeesPage } from "./EmployeesPage";

const { useMutationMock, useQueryMock } = vi.hoisted(() => ({
  useMutationMock: vi.fn(),
  useQueryMock: vi.fn()
}));

vi.mock("@tanstack/react-query", () => ({
  useMutation: useMutationMock,
  useQuery: useQueryMock,
  useQueryClient: () => ({
    invalidateQueries: vi.fn(),
    setQueryData: vi.fn()
  })
}));

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({
    selectedStore: { id: "store-1", name: "МАГАЗИН", timezone: "Europe/Kaliningrad" },
    periodStart: "2026-08-01",
    periodEnd: "2026-08-31"
  })
}));

function queryResult(data: unknown) {
  return { data, error: null, isError: false, isPending: false, refetch: vi.fn() };
}

const settings = [
  {
    employeeId: "employee-1",
    displayName: "Анна",
    participatesInRanking: true,
    employeeActive: true,
    assignmentActive: true,
    version: 1
  },
  {
    employeeId: "employee-2",
    displayName: "Борис",
    participatesInRanking: false,
    employeeActive: true,
    assignmentActive: true,
    version: 2
  },
  {
    employeeId: "employee-3",
    displayName: "Вера",
    participatesInRanking: false,
    employeeActive: false,
    assignmentActive: true,
    version: 3
  }
];

describe("employees participation disclosure", () => {
  beforeEach(() => {
    useMutationMock.mockReset().mockReturnValue({
      error: null,
      isError: false,
      isPending: false,
      mutate: vi.fn(),
      variables: undefined
    });
    useQueryMock.mockReset().mockImplementation(({ queryKey }: { queryKey: readonly unknown[] }) => {
      if (queryKey.includes("directory")) return queryResult({ employees: [] });
      if (queryKey.includes("rating")) return queryResult({
        formula: { minimumCoveragePercent: 70 },
        history: { status: "LIVE" },
        plan: { complete: true, coveragePercent: 100, revenueAchievementPercent: 100 }
      });
      if (queryKey.includes("settings")) return queryResult(settings);
      return queryResult(undefined);
    });
  });

  it("places participants below results and keeps the block collapsed by default", () => {
    render(<MemoryRouter><EmployeesPage /></MemoryRouter>);

    const results = screen.getByRole("heading", { name: "Результаты сотрудников" });
    const participants = screen.getByRole("heading", { name: "Участники рейтинга и смен" });
    const disclosure = participants.closest("details");

    expect(disclosure).not.toBeNull();
    expect(disclosure).not.toHaveAttribute("open");
    expect(results.compareDocumentPosition(participants) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();

    fireEvent.click(within(disclosure!).getByText("Участники рейтинга и смен"));
    expect(disclosure).toHaveAttribute("open");
  });

  it("uses the toggle as the status and keeps only actionable unavailability reasons", () => {
    render(<MemoryRouter><EmployeesPage /></MemoryRouter>);

    expect(screen.queryByText("Участвует в рейтинге и доступен для смен"))
      .not.toBeInTheDocument();
    expect(screen.queryByText("Не участвует и недоступен для новых смен"))
      .not.toBeInTheDocument();
    expect(screen.getByText("Профиль неактивен")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Включен" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("button", { name: "Выключен" })).toHaveLength(2);
  });
});
