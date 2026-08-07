export type InsightTone = "positive" | "neutral" | "warning";

const horizonLabels: Readonly<Record<string, string>> = {
  CURRENT_WEEK: "На этой неделе",
  NEXT_WEEK: "На следующей неделе",
  NEXT_30_DAYS: "В течение месяца"
};

const actionTypeLabels: Readonly<Record<string, string>> = {
  PEER_LEARNING: "Обмен опытом",
  COACHING: "Разбор с руководителем",
  CATEGORY_FOCUS: "Фокус на категории",
  ADDITIONAL_SALES_FOCUS: "Дополнительные продажи",
  DATA_QUALITY_CHECK: "Проверка данных",
  MONITORING: "Наблюдение"
};

const analysisStatusLabels: Readonly<Record<string, string>> = {
  SUFFICIENT: "Данных достаточно",
  INSUFFICIENT: "Нужно больше данных"
};

export function actionHorizonLabel(horizon: string): string {
  return horizonLabels[horizon] ?? "Ближайший период";
}

export function actionTypeLabel(type: string): string {
  return actionTypeLabels[type] ?? "Практический шаг";
}

export function analysisStatusLabel(status: string): string {
  return analysisStatusLabels[status] ?? "Статус анализа";
}

export function analysisStatusTone(status: string): InsightTone {
  return status === "SUFFICIENT" ? "positive" : status === "INSUFFICIENT" ? "warning" : "neutral";
}

export function uniqueNarratives<T>(
  items: readonly T[],
  text: (item: T) => string
): T[] {
  const seen = new Set<string>();
  return items.filter((item) => {
    const normalized = text(item).trim().replace(/\s+/gu, " ").toLocaleLowerCase("ru-RU");
    if (!normalized || seen.has(normalized)) return false;
    seen.add(normalized);
    return true;
  });
}

export function limitationSummary(value: unknown): string {
  if (typeof value === "object" && value !== null && "summary" in value) {
    const summary = Reflect.get(value, "summary");
    if (typeof summary === "string" && summary.trim()) return summary.trim();
  }
  return "Части данных пока недостаточно для уверенного вывода.";
}
