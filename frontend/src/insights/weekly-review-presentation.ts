import type {
  WeeklyReviewAction,
  WeeklyReviewEvidence,
  WeeklyReviewMetric
} from "../api/weeklyReviewContract";
import { formatMoney, formatNumber, formatPercent } from "../shared/format";

export type ReviewTone = "positive" | "negative" | "neutral" | "limited";

function signed(value: number, formatted: string): string {
  if (value === 0) return formatted;
  return `${value > 0 ? "+" : "−"}${formatted}`;
}

export function formatValue(
  value: number | null | undefined,
  unit: WeeklyReviewMetric["unit"]
): string {
  if (value == null) return "—";
  if (unit === "RUB") return formatMoney(value);
  if (unit === "PERCENT") return formatPercent(value);
  if (unit === "PER_100") return `${formatNumber(value)} на 100`;
  if (unit === "HOURS") return `${formatNumber(value)} ч`;
  return formatNumber(value);
}

export function formatEvidenceValue(
  value: WeeklyReviewEvidence["currentValue"],
  unit: WeeklyReviewEvidence["unit"]
): string {
  if (typeof value === "number") return formatValue(value, unit);
  if (typeof value === "boolean") return value ? "Да" : "Нет";
  return value == null ? "—" : value;
}

export function metricComparisonText(metric: WeeklyReviewMetric): string {
  if (metric.metricState === "UNAVAILABLE" || metric.current == null) {
    return "Недостаточно данных";
  }
  if (metric.direction === "FLAT") return "Без изменений";
  if (metric.comparisonKind === "PERCENT_AVAILABLE" && metric.changePercent != null) {
    return signed(
      metric.changePercent,
      formatPercent(Math.abs(metric.changePercent))
    );
  }
  if (metric.comparisonKind === "NO_BASE") return "Нет базы сравнения";
  if (metric.comparisonKind === "NON_POSITIVE_BASE") {
    return metric.absoluteDelta == null
      ? "Сравнение недоступно"
      : signed(
        metric.absoluteDelta,
        formatValue(Math.abs(metric.absoluteDelta), metric.unit)
      );
  }
  if (metric.absoluteDelta != null) {
    return signed(
      metric.absoluteDelta,
      formatValue(Math.abs(metric.absoluteDelta), metric.unit)
    );
  }
  return "Сравнение недоступно";
}

export function metricTone(metric: WeeklyReviewMetric): ReviewTone {
  if (metric.metricState === "LIMITED" || metric.metricState === "UNAVAILABLE") {
    return "limited";
  }
  if (metric.effect === "POSITIVE") return "positive";
  if (metric.effect === "NEGATIVE") return "negative";
  return "neutral";
}

export function metricStateText(metric: WeeklyReviewMetric): string | null {
  if (metric.metricState === "LIMITED") return "Данные требуют проверки";
  if (metric.metricState === "UNAVAILABLE") return "Значение недоступно";
  if (metric.sufficiency === "INSUFFICIENT") return "Недостаточно данных";
  return null;
}

export function actionTargetText(action: WeeklyReviewAction): string {
  const operator = action.target.operator === "AT_MOST" ? "не выше" : "не ниже";
  return `${operator} ${formatValue(
    action.target.value,
    action.target.unit
  )}`;
}

export function reviewStateLabel(
  state: "PREPARING" | "READY" | "PARTIAL" | "BLOCKED"
): string {
  if (state === "READY") return "Данные готовы";
  if (state === "PARTIAL") return "Есть ограничения";
  if (state === "BLOCKED") return "Нужны данные";
  return "Формируется";
}

export function sourceLabel(sourceCode: string): string {
  const labels: Readonly<Record<string, string>> = {
    SALES: "Продажи",
    RETURNS: "Возвраты",
    CLASSIFICATION: "Классификация",
    COST: "Себестоимость",
    EMPLOYEE_ATTRIBUTION: "Продавцы",
    SHIFTS: "Смены"
  };
  return labels[sourceCode] ?? "Источник данных";
}

export function initials(displayName: string): string {
  return displayName
    .trim()
    .split(/\s+/u)
    .slice(0, 2)
    .map((part) => part[0]?.toLocaleUpperCase("ru-RU") ?? "")
    .join("");
}

export function formatCalculatedAt(value: string): string {
  return new Intl.DateTimeFormat("ru-RU", {
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(value));
}
