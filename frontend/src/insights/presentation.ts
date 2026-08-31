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
    ? "Возможная причина — ее нужно проверить по дополнительным данным."
    : null;
}

export function employeeAnalysisHelp(status: string): string {
  if (status === "INSUFFICIENT") {
    return "Недостаточно подтвержденных смен или покрытия рейтинговых метрик за неделю. Продажи учтены в показателях магазина, но персональные выводы без этой основы были бы ненадежными.";
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

type ComparableInsight = {
  candidateRef?: string | null;
  title: string;
  summary: string;
  evidenceRefs?: readonly string[];
};

function normalizedNarrative(value: string): string {
  return value.trim().replace(/\s+/gu, " ").toLocaleLowerCase("ru-RU");
}

function sameEvidenceSet(
  left: readonly string[] | undefined,
  right: readonly string[] | undefined
): boolean {
  if (!left?.length || !right?.length || left.length !== right.length) return false;
  const leftSet = new Set(left);
  return right.every((reference) => leftSet.has(reference));
}

export function uniqueInsightSignals<T extends ComparableInsight>(
  items: readonly (T | null | undefined)[]
): T[] {
  const result: T[] = [];
  items.forEach((item) => {
    if (!item) return;
    const duplicate = result.some((existing) => {
      const sameCandidate = Boolean(
        item.candidateRef
          && existing.candidateRef
          && item.candidateRef === existing.candidateRef
      );
      const sameNarrative = normalizedNarrative(item.title + "\n" + item.summary)
        === normalizedNarrative(existing.title + "\n" + existing.summary);
      return sameCandidate
        || sameNarrative
        || sameEvidenceSet(item.evidenceRefs, existing.evidenceRefs);
    });
    if (!duplicate) result.push(item);
  });
  return result;
}

export function limitationSummary(value: unknown): string {
  if (typeof value === "object" && value !== null && "summary" in value) {
    const summary = Reflect.get(value, "summary");
    if (typeof summary === "string" && summary.trim()) return summary.trim();
  }
  return "Части данных пока недостаточно для уверенного вывода.";
}

export function readableInsightText(value: string): string {
  return value.replace(/\s*·\s*/gu, ", ");
}
