import type { EmployeeDirectoryEntry, EmployeeRatingEntry } from "../api/contracts";

export type EmployeeFilter = "all" | "ranked" | "attention";
export type EmployeeSort = "rank" | "score" | "revenue" | "improvement";

export const attachRateLabels: Record<string, string> = {
  ACCESSORY_IPAD: "Аксессуары к iPad",
  ACCESSORY_PODS_WATCH: "Аксессуары к Pods / Watch",
  CASE_APPLE_IPHONE: "Чехлы Apple / iPhone",
  CASE_SAMSUNG: "Чехлы Samsung",
  CHARGER_CABLE: "Зарядные устройства и кабели",
  FILM_PHONE: "Защитные пленки",
  GLASS_IPHONE: "Защитное стекло iPhone",
  GLASS_CAMERA_IPHONE: "Защита камеры iPhone",
  GLASS_SAMSUNG: "Защитное стекло Samsung",
  GLASS_CAMERA_SAMSUNG: "Защита камеры Samsung",
  PREMIUM_PROTECTION: "Протекция",
  SETUP_SERVICE: "Настройки и услуги",
  WARRANTY_GENERIC_NEW: "Гарантии — новые устройства",
  WARRANTY_GENERIC_USED: "Гарантии — устройства Б/У"
};

export const scoreLabels = {
  contributionScore: "Коммерческий вклад",
  efficiencyScore: "Эффективность времени",
  structureScore: "Структура продаж",
  attachScore: "Интенсивность допродаж"
} as const;

export function employeeRatingReason(employee: EmployeeRatingEntry, minimumCoverage = 75): string {
  if (!employee.employeeActive) return "Профиль неактивен";
  if (!employee.assignmentActive) return "Нет активного назначения";
  if (!employee.participatesInRanking) return "Не участвует";
  if (employee.shiftCount === 0) return "Нет смен за период";
  if (!employee.ratingEligible) return "Не соответствует условиям";
  if (employee.scores.coveragePercent < minimumCoverage) return "Недостаточно данных";
  return employee.ranked ? `Место ${employee.rank ?? "—"}` : "Место не присвоено";
}

function compareNullableDesc(left: number | null, right: number | null): number {
  if (left == null && right == null) return 0;
  if (left == null) return 1;
  if (right == null) return -1;
  return right - left;
}
function compareRank(left: number | null, right: number | null): number {
  if (left == null && right == null) return 0;
  if (left == null) return 1;
  if (right == null) return -1;
  return left - right;
}


export function selectEmployeeEntries(
  entries: EmployeeDirectoryEntry[],
  search: string,
  filter: EmployeeFilter,
  sort: EmployeeSort
): EmployeeDirectoryEntry[] {
  const normalizedSearch = search.trim().toLocaleLowerCase("ru-RU");
  const filtered = entries.filter(({ current }) => {
    if (!current.participatesInRanking) return false;
    if (normalizedSearch && !current.displayName.toLocaleLowerCase("ru-RU").includes(normalizedSearch)) return false;
    if (filter === "ranked") return current.ranked;
    if (filter === "attention") return !current.ranked;
    return true;
  });

  return [...filtered].sort((left, right) => {
    let result = 0;
    if (sort === "rank") result = compareRank(left.current.rank, right.current.rank);
    if (sort === "score") result = compareNullableDesc(left.current.scores.overallScore, right.current.scores.overallScore);
    if (sort === "revenue") result = right.current.netRevenue - left.current.netRevenue;
    if (sort === "improvement") result = compareNullableDesc(left.dynamics.rankImprovement, right.dynamics.rankImprovement);
    return result || left.current.displayName.localeCompare(right.current.displayName, "ru-RU");
  });
}
