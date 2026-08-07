import type { StoreQualityDetail } from "./api";

export type StoreQualityIssue = StoreQualityDetail["issues"][number];

export interface StoreQualityIssueGroup {
  key: string;
  source: StoreQualityIssue["source"];
  code: string;
  severity: StoreQualityIssue["severity"];
  recommendedAction: StoreQualityIssue["recommendedAction"];
  eventCount: number;
  latestDetectedAt: string | null;
}

function latest(first: string | null, second: string | null): string | null {
  if (first == null) return second;
  if (second == null) return first;
  return first > second ? first : second;
}

export function groupStoreQualityIssues(
  issues: StoreQualityIssue[]
): StoreQualityIssueGroup[] {
  const groups = new Map<string, StoreQualityIssueGroup>();

  for (const issue of issues) {
    const key = [
      issue.severity,
      issue.source,
      issue.code,
      issue.recommendedAction
    ].join(":");
    const existing = groups.get(key);
    if (existing) {
      groups.set(key, {
        ...existing,
        eventCount: existing.eventCount + 1,
        latestDetectedAt: latest(existing.latestDetectedAt, issue.detectedAt)
      });
    } else {
      groups.set(key, {
        key,
        source: issue.source,
        code: issue.code,
        severity: issue.severity,
        recommendedAction: issue.recommendedAction,
        eventCount: 1,
        latestDetectedAt: issue.detectedAt
      });
    }
  }

  return [...groups.values()];
}

export function qualityIssueGuidance(code: string): string {
  if (code === "ZERO_UNEXPECTED_COST" || code === "RETURN_ZERO_UNEXPECTED_COST") {
    return "Проверьте категорию и себестоимость позиции в LiveSklad. Для услуг, гарантий и протекций нулевая себестоимость допустима; после исправления источника или категории запустите синхронизацию.";
  }
  if (code === "UNMAPPED_PRODUCT") {
    return "Назначьте товару аналитическую категорию и повторите синхронизацию данных.";
  }
  return "Проверьте исходный документ в LiveSklad и повторите синхронизацию. Если предупреждение останется, передайте код проблемы администратору.";
}
