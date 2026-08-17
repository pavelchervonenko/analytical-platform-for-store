import type { CategoryKpiEntry } from "../api/contracts";

export function averageGrossProfitPerDeviceUnit(
  category: CategoryKpiEntry
): number | null {
  if (!category.countsAsDevice) return null;
  return category.metrics.averageGrossProfitPerUnit;
}
