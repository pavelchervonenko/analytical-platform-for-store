import type { PayrollReadiness } from "../api/contracts";
import type { PayrollCategory } from "./api";

export const payrollCategoryOptions: ReadonlyArray<{
  value: PayrollCategory;
  label: string;
}> = [
  { value: "TECH_TIER_1", label: "Техника, уровень 1" },
  { value: "TECH_TIER_2", label: "Техника, уровень 2" },
  { value: "ACCESSORY", label: "Аксессуар" },
  { value: "SERVICE", label: "Услуга" },
  { value: "PLAYSTATION_SUBSCRIPTION", label: "Подписка PlayStation" },
  { value: "PAID_REPAIR", label: "Платный ремонт" },
  { value: "EXCLUDE", label: "Исключить из зарплаты" }
];

export type UnmappedPayrollProduct =
  PayrollReadiness["unmappedProducts"][number];
export type ClassificationSelections =
  Record<string, PayrollCategory | "">;

export function isPayrollCategory(
  value: string | null | undefined
): value is PayrollCategory {
  return value != null
    && payrollCategoryOptions.some((category) => category.value === value);
}

export function payrollCategoryOptionLabel(value: PayrollCategory): string {
  return payrollCategoryOptions.find((category) => category.value === value)?.label
    ?? value;
}

export function recommendedPayrollCategory(
  product: UnmappedPayrollProduct
): PayrollCategory | "" {
  return isPayrollCategory(product.suggestedCategoryCode)
    ? product.suggestedCategoryCode
    : "";
}

export function selectedPayrollCategory(
  product: UnmappedPayrollProduct,
  selections: ClassificationSelections
): PayrollCategory | "" {
  if (Object.prototype.hasOwnProperty.call(selections, product.productId)) {
    return selections[product.productId] ?? "";
  }
  return recommendedPayrollCategory(product);
}

export function buildClassificationAssignments(
  products: UnmappedPayrollProduct[],
  selections: ClassificationSelections
): Array<{ productId: string; categoryCode: PayrollCategory }> {
  return products.flatMap((product) => {
    const categoryCode = selectedPayrollCategory(product, selections);
    return categoryCode
      ? [{ productId: product.productId, categoryCode }]
      : [];
  });
}
