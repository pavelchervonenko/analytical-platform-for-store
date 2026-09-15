import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import type { PayrollReadiness } from "../api/contracts";
import { ReadinessBanner } from "./PayrollPage";

const readiness = {
  storeId: "00000000-0000-4000-8000-000000000001",
  periodMonth: "2026-07",
  status: "BLOCKED",
  canCalculate: false,
  canApprove: false,
  planPresent: false,
  schemePresent: true,
  planResult: null,
  scheduledDayCount: 2,
  daysWithoutShift: 1,
  salesDayCount: 3,
  unmappedItemCount: 0,
  missingCostItemCount: 0,
  unmappedProducts: [],
  missingCosts: [],
  shiftIssues: []
} satisfies PayrollReadiness;

describe("payroll readiness permissions", () => {
  it("does not link to plan or shifts when those actions are unavailable", () => {
    render(
      <MemoryRouter>
        <ReadinessBanner
          readiness={readiness}
          run={null}
          search="store=store-1&month=2026-07"
          isAdmin={false}
          canOpenPlan={false}
          canOpenShifts={false}
        />
      </MemoryRouter>
    );

    expect(screen.queryByRole("link", { name: "Заполнить" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Исправить" })).not.toBeInTheDocument();
    expect(screen.getByText("Настроит руководитель с доступом")).toBeInTheDocument();
    expect(screen.getByText("Исправит руководитель с доступом")).toBeInTheDocument();
  });

  it("keeps corrective links for a manager with both actions", () => {
    render(
      <MemoryRouter>
        <ReadinessBanner
          readiness={readiness}
          run={null}
          search="store=store-1&month=2026-07"
          isAdmin={false}
          canOpenPlan
          canOpenShifts
        />
      </MemoryRouter>
    );

    expect(screen.getByRole("link", { name: "Заполнить" }))
      .toHaveAttribute("href", "/plan?store=store-1&month=2026-07");
    expect(screen.getByRole("link", { name: "Исправить" }))
      .toHaveAttribute("href", "/shifts?store=store-1&month=2026-07");
  });
});
