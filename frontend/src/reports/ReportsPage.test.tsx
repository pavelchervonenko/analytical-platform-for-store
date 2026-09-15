import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SummaryCards } from "./ReportsPage";

describe("report summary", () => {
  it("explains unavailable profit metrics once", () => {
    render(<SummaryCards revenue={1_000_000} grossProfit={null} margin={null} payroll={120_000} quantity={24} />);

    expect(screen.getAllByText("—")).toHaveLength(2);
    expect(screen.getByText(/себестоимости пока недостаточно/u)).toBeInTheDocument();
    expect(screen.queryByText(/администратор/u)).not.toBeInTheDocument();
  });

  it("does not show a data note when all metrics are available", () => {
    render(<SummaryCards revenue={1_000_000} grossProfit={300_000} margin={30} payroll={120_000} quantity={24} />);

    expect(screen.queryByText(/себестоимости пока недостаточно/u)).not.toBeInTheDocument();
  });
});
