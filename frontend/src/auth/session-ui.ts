import type { ActiveSession } from "../api/contracts";

export function orderActiveSessions(sessions: ActiveSession[]): ActiveSession[] {
  return [...sessions].sort((left, right) => {
    if (left.current !== right.current) return left.current ? -1 : 1;
    return Date.parse(right.lastSeenAt) - Date.parse(left.lastSeenAt);
  });
}

export function formatSessionActivity(value: string): string {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}
