export type InsightTone = "positive" | "neutral" | "warning";
export type InsightKindTone = "observation" | "synthesis" | "hypothesis"
  | "risk" | "opportunity" | "unknown";

const horizonLabels: Readonly<Record<string, string>> = {
  CURRENT_WEEK: "На этой неделе",
  NEXT_WEEK: "На следующей неделе",
  NEXT_30_DAYS: "В течение месяца",
  MONTH_END: "До конца месяца",
  MONITORING_PERIOD: "На период наблюдения"
};

const actionTypeLabels: Readonly<Record<string, string>> = {
  PEER_LEARNING: "Обмен опытом",
  COACHING: "Разбор с руководителем",
  CATEGORY_FOCUS: "Фокус на категории",
  ADDITIONAL_SALES_FOCUS: "Дополнительные продажи",
  PROCESS_REVIEW: "Проверка процесса",
  DATA_QUALITY_CHECK: "Проверка данных",
  MONITORING: "Наблюдение",
  INVESTIGATION: "Дополнительная проверка"
};

const analysisStatusLabels: Readonly<Record<string, string>> = {
  SUFFICIENT: "Данных достаточно",
  LIMITED: "Данные ограничены",
  INSUFFICIENT: "Нужно больше данных"
};

const insightKindLabels: Readonly<Record<string, string>> = {
  OBSERVATION: "Наблюдение",
  SYNTHESIS: "Интерпретация",
  HYPOTHESIS: "Гипотеза",
  RISK: "Риск",
  OPPORTUNITY: "Возможность"
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

export function insightKindLabel(kind: string): string {
  return insightKindLabels[kind] ?? "Вывод";
}

export function insightKindTone(kind: string): InsightKindTone {
  switch (kind) {
    case "OBSERVATION": return "observation";
    case "SYNTHESIS": return "synthesis";
    case "HYPOTHESIS": return "hypothesis";
    case "RISK": return "risk";
    case "OPPORTUNITY": return "opportunity";
    default: return "unknown";
  }
}

export function insightKindHelp(kind: string): string | null {
  return kind === "HYPOTHESIS"
    ? "Возможная причина — её нужно проверить по дополнительным данным."
    : null;
}

export function employeeAnalysisHelp(status: string): string {
  if (status === "INSUFFICIENT") {
    return "Недостаточно подтверждённых смен или покрытия рейтинговых метрик за неделю. Продажи учтены в показателях магазина, но персональные выводы без этой основы были бы ненадёжными.";
  }
  if (status === "LIMITED") {
    return "Разбор построен по доступным данным, но отдельные выводы имеют пониженную уверенность.";
  }
  return "Данных достаточно для персонального разбора.";
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
