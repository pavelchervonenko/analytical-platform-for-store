import { describe, expect, it } from "vitest";
import {
  buildClassificationAssignments,
  payrollCategoryOptionLabel,
  recommendedPayrollCategory,
  selectedPayrollCategory,
  type UnmappedPayrollProduct
} from "./classification";

const MACBOOK_ID = "00000000-0000-4000-8000-000000000001";
const IPAD_ID = "00000000-0000-4000-8000-000000000002";

function product(
  productId: string,
  suggestedCategoryCode: string | null
): UnmappedPayrollProduct {
  return {
    productId,
    productName: "Тестовый товар",
    analyticsCategoryCode: "IPAD_MAC",
    firstSaleDate: "2026-08-03",
    lastSaleDate: "2026-08-03",
    saleItemCount: 1,
    returnItemCount: 0,
    netQuantity: 1,
    netRevenue: 1000,
    suggestedCategoryCode,
    suggestionReason: "Проверьте рекомендацию."
  };
}

describe("payroll classification suggestions", () => {
  it("предварительно выбирает поддерживаемые рекомендации", () => {
    const macBook = product(MACBOOK_ID, "TECH_TIER_1");
    const ipad = product(IPAD_ID, "TECH_TIER_2");

    expect(recommendedPayrollCategory(macBook)).toBe("TECH_TIER_1");
    expect(selectedPayrollCategory(ipad, {})).toBe("TECH_TIER_2");
    expect(buildClassificationAssignments([macBook, ipad], {})).toEqual([
      { productId: MACBOOK_ID, categoryCode: "TECH_TIER_1" },
      { productId: IPAD_ID, categoryCode: "TECH_TIER_2" }
    ]);
    expect(payrollCategoryOptionLabel("TECH_TIER_1"))
      .toBe("Техника, уровень 1");
  });

  it("сохраняет ручное снятие и изменение рекомендации", () => {
    const macBook = product(MACBOOK_ID, "TECH_TIER_1");
    const ipad = product(IPAD_ID, "TECH_TIER_2");
    const selections = {
      [MACBOOK_ID]: "",
      [IPAD_ID]: "EXCLUDE"
    } as const;

    expect(selectedPayrollCategory(macBook, selections)).toBe("");
    expect(buildClassificationAssignments([macBook, ipad], selections)).toEqual([
      { productId: IPAD_ID, categoryCode: "EXCLUDE" }
    ]);
  });

  it("не применяет неизвестный код как зарплатную категорию", () => {
    const futureCategory = product(MACBOOK_ID, "FUTURE_CATEGORY");

    expect(recommendedPayrollCategory(futureCategory)).toBe("");
    expect(buildClassificationAssignments([futureCategory], {})).toEqual([]);
  });
});
