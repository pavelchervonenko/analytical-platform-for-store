import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { CategoryKpi } from "../api/contracts";
import { SalesMixSummary } from "./OverviewPage";

function group(
  groupCode: string,
  netRevenue: number,
  netQuantity: number
): CategoryKpi["groups"][number] {
  return {
    groupCode,
    groupName: groupCode,
    metrics: {
      netRevenue,
      netQuantity,
      costAmount: 0,
      grossProfit: netRevenue,
      averageGrossProfitPerUnit: netQuantity > 0 ? netRevenue / netQuantity : null,
      marginPercent: 100,
      dataQuality: {
        completeCostData: true,
        includedItemCount: 1,
        missingCostItemCount: 0,
        unexpectedZeroCostItemCount: 0
      }
    }
  };
}

describe("SalesMixSummary", () => {
  it("shows accessory and service turnover with net quantities", () => {
    render(
      <div>
        <SalesMixSummary
          groups={[
            group("ACCESSORY", 12000, 8),
            group("SERVICE", 7500, 5),
            group("ADDITIONAL_REVENUE", 19500, 13)
          ]}
        />
      </div>
    );

    const accessories = screen.getByText("Аксессуары").closest("article");
    const services = screen.getByText("Услуги").closest("article");

    expect(accessories).not.toBeNull();
    expect(within(accessories!).getByText("12 000 ₽")).toBeInTheDocument();
    expect(within(accessories!).getByText("8 ед.")).toBeInTheDocument();
    expect(services).not.toBeNull();
    expect(within(services!).getByText("7 500 ₽")).toBeInTheDocument();
    expect(within(services!).getByText("5 ед.")).toBeInTheDocument();
    expect(screen.queryByText("Средний чек")).not.toBeInTheDocument();
  });
});
