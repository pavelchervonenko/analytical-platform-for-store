import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createRef } from "react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClientError } from "../api/client";
import { ShiftDayEditor } from "./SchedulePanel";

const { getWorkScheduleDayMock, replaceWorkScheduleDayMock } = vi.hoisted(() => ({
  getWorkScheduleDayMock: vi.fn(),
  replaceWorkScheduleDayMock: vi.fn()
}));

vi.mock("../api/queries", () => ({
  getWorkScheduleDay: getWorkScheduleDayMock,
  replaceWorkScheduleDay: replaceWorkScheduleDayMock,
  queryKeys: { employees: (storeId: string) => ["stores", storeId, "employees"] }
}));

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({
    selectedStore: { id: "store-1", name: "МАГАЗИН", timezone: "Europe/Kaliningrad" },
    month: "2026-09"
  })
}));

const settings = [
  {
    employeeId: "employee-1",
    displayName: "Анна",
    participatesInRanking: true,
    employeeActive: true,
    assignmentActive: true,
    version: 1,
    updatedAt: "2026-09-15T10:00:00Z"
  },
  {
    employeeId: "employee-2",
    displayName: "Борис",
    participatesInRanking: true,
    employeeActive: true,
    assignmentActive: true,
    version: 1,
    updatedAt: "2026-09-15T10:00:00Z"
  }
];

function renderEditor(onSaved = vi.fn()) {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } }
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ShiftDayEditor
          workDate="2026-09-15"
          dayShifts={[{
            id: "shift-1",
            employeeId: "employee-1",
            employeeName: "Анна",
            workDate: "2026-09-15",
            workedHours: 11,
            active: true,
            version: 1
          }]}
          etag={'"schedule-v1"'}
          settings={settings}
          scheduleKey={["stores", "store-1", "work-schedule"]}
          returnFocusRef={createRef<HTMLButtonElement>()}
          onClose={vi.fn()}
          onSaved={onSaved}
        />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe("shift editor shared ownership", () => {
  beforeEach(() => {
    getWorkScheduleDayMock.mockReset();
    replaceWorkScheduleDayMock.mockReset();
  });

  it("reloads a concurrently changed day and lets the current manager clear it", async () => {
    replaceWorkScheduleDayMock
      .mockRejectedValueOnce(new ApiClientError("stale", {
        status: 412,
        code: "PRECONDITION_FAILED"
      }))
      .mockResolvedValueOnce({
        etag: '"schedule-v3"',
        value: { storeId: "store-1", workDate: "2026-09-15", revision: 3, shifts: [] }
      });
    getWorkScheduleDayMock.mockResolvedValue({
      etag: '"schedule-v2"',
      value: {
        storeId: "store-1",
        workDate: "2026-09-15",
        revision: 2,
        shifts: [{
          id: "shift-2",
          employeeId: "employee-2",
          employeeName: "Борис",
          workDate: "2026-09-15",
          workedHours: 5,
          active: true,
          version: 1
        }]
      }
    });
    const onSaved = vi.fn();
    renderEditor(onSaved);

    const annaRow = screen.getByText("Анна").closest("article")!;
    fireEvent.change(within(annaRow).getByRole("textbox"), { target: { value: "8" } });
    fireEvent.click(screen.getByRole("button", { name: "Сохранить день" }));

    expect(await screen.findByText(/Мы загрузили актуальную версию/u)).toBeInTheDocument();
    expect(getWorkScheduleDayMock).toHaveBeenCalledWith("store-1", "2026-09-15");
    expect(replaceWorkScheduleDayMock).toHaveBeenNthCalledWith(
      1,
      "store-1",
      "2026-09-15",
      '"schedule-v1"',
      [{ employeeId: "employee-1", workedHours: 8 }]
    );

    const borisRow = screen.getByText("Борис").closest("article")!;
    expect(within(borisRow).getByRole("textbox")).toHaveValue("5");
    fireEvent.click(screen.getByRole("button", { name: "Очистить день" }));
    fireEvent.click(screen.getByRole("button", { name: "Подтвердить" }));

    await waitFor(() => expect(replaceWorkScheduleDayMock).toHaveBeenNthCalledWith(
      2,
      "store-1",
      "2026-09-15",
      '"schedule-v2"',
      []
    ));
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("2026-09-15"));
  });
});
