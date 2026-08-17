import { describe, expect, it } from "vitest";
import type { CategoryKpiEntry } from "../api/contracts";
import { averageGrossProfitPerDeviceUnit } from "./categoryPresentation";

function category(
  countsAsDevice: boolean,
  averageGrossProfitPerUnit: number | null
): CategoryKpiEntry {
  return {
    categoryCode: "TEST",
    categoryName: "Тест",
    categoryKind: countsAsDevice ? "DEVICE" : "ACCESSORY",
    deviceFamily: countsAsDevice ? "IPHONE" : null,
    categoryActive: true,
    countsAsPhone: countsAsDevice,
    countsAsDevice,
    countsAsAdditionalRevenue: !countsAsDevice,
    metrics: {
      netRevenue: 100,
      netQuantity: 2,
      costAmount: 40,
      grossProfit: 60,
      averageGrossProfitPerUnit,
      marginPercent: 60,
      dataQuality: {
        completeCostData: true,
        includedItemCount: 2,
        missingCostItemCount: 0,
        unexpectedZeroCostItemCount: 0
      }
    }
  };
}

describe("averageGrossProfitPerDeviceUnit", () => {
  it("returns the backend value for a device category", () => {
    expect(averageGrossProfitPerDeviceUnit(category(true, 30))).toBe(30);
  });

  it("does not present the metric for non-device categories", () => {
    expect(averageGrossProfitPerDeviceUnit(category(false, 30))).toBeNull();
  });

  it("keeps an unknown device value unknown", () => {
    expect(averageGrossProfitPerDeviceUnit(category(true, null))).toBeNull();
  });
});
