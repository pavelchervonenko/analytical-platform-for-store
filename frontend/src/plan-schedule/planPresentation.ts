import type { OverviewMetricScope, PlanDirection } from "../api/contracts";
import { formatCompactMoney, formatPercent } from "../shared/format";

export const planDirectionLabels: Record<string, string> = {
  REVENUE: "Выручка",
  ACCESSORY: "Аксессуары",
  SERVICE: "Услуги",
  ADDITIONAL: "Доп. выручка"
};

export function planScopeLabel(scope: OverviewMetricScope): string {
  return scope === "SELLERS" ? "только продавцы" : "весь магазин";
}

export function isRevenueDirection(direction: PlanDirection): boolean {
  return direction.criterionType === "AMOUNT" || direction.code === "REVENUE";
}

export function hasRevenueForecast(direction: PlanDirection): boolean {
  return direction.status !== "NOT_AVAILABLE"
    && direction.projectedAmountCompletionPercent != null;
}

export function formatPercentagePoints(value: number | null): string {
  if (value == null) return "—";
  const formatted = new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: 1,
    signDisplay: "exceptZero"
  }).format(value).replace("-", "−");
  return `${formatted} п. п.`;
}

export function formatAbsolutePercentagePoints(value: number): string {
  const formatted = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 })
    .format(Math.abs(value));
  return `${formatted} п. п.`;
}

export function revenueForecastValue(direction: PlanDirection): string {
  return `${formatCompactMoney(direction.projectedAmount)}, ${formatPercent(direction.projectedAmountCompletionPercent)} плана`;
}
