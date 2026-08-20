import { isIsoDate, monthFromDate, monthRange, shiftDate, shiftMonth, weekRange } from "../shared/date";

export type QuickPeriodCode =
  | "TODAY"
  | "YESTERDAY"
  | "LAST_7_DAYS"
  | "LAST_30_DAYS"
  | "THIS_WEEK"
  | "PREVIOUS_WEEK"
  | "THIS_MONTH"
  | "PREVIOUS_MONTH";

export interface CalendarDay {
  date: string;
  inCurrentMonth: boolean;
}

export interface CalendarRange {
  start: string;
  end: string;
}

export interface QuickPeriod {
  code: QuickPeriodCode;
  label: string;
  range: CalendarRange;
  disabled: boolean;
}

export function buildCalendarMonth(month: string): CalendarDay[] {
  const { start } = monthRange(month);
  const weekday = new Date(`${start}T00:00:00Z`).getUTCDay();
  const mondayOffset = weekday === 0 ? 6 : weekday - 1;
  const firstCell = shiftDate(start, -mondayOffset);
  return Array.from({ length: 42 }, (_, index) => {
    const date = shiftDate(firstCell, index);
    return { date, inCurrentMonth: monthFromDate(date) === month };
  });
}

export function orderedRange(first: string, second: string): CalendarRange {
  if (!isIsoDate(first) || !isIsoDate(second)) throw new Error("Invalid ISO range");
  return first <= second
    ? { start: first, end: second }
    : { start: second, end: first };
}

export function quickPeriods(today: string, dataThroughDate?: string | null): QuickPeriod[] {
  if (!isIsoDate(today)) return [];
  const coveredThrough = isIsoDate(dataThroughDate ?? null) && dataThroughDate! < today
    ? dataThroughDate!
    : today;
  const currentWeek = weekRange(today);
  const previousWeek = weekRange(shiftDate(currentWeek.start, -1));
  const currentMonth = monthRange(monthFromDate(today));
  const previousMonth = monthRange(shiftMonth(monthFromDate(today), -1));
  const coverageInCurrentWeek = coveredThrough >= currentWeek.start;
  const coverageInCurrentMonth = coveredThrough >= currentMonth.start;

  return [
    period("TODAY", "Сегодня", today, today),
    period("YESTERDAY", "Вчера", shiftDate(today, -1), shiftDate(today, -1)),
    period("LAST_7_DAYS", "7 дней", shiftDate(coveredThrough, -6), coveredThrough),
    period("LAST_30_DAYS", "30 дней", shiftDate(coveredThrough, -29), coveredThrough),
    period(
      "THIS_WEEK",
      "Эта неделя",
      currentWeek.start,
      coverageInCurrentWeek ? coveredThrough : currentWeek.start,
      !coverageInCurrentWeek
    ),
    period("PREVIOUS_WEEK", "Прошлая неделя", previousWeek.start, previousWeek.end),
    period(
      "THIS_MONTH",
      "Этот месяц",
      currentMonth.start,
      coverageInCurrentMonth ? coveredThrough : currentMonth.start,
      !coverageInCurrentMonth
    ),
    period("PREVIOUS_MONTH", "Прошлый месяц", previousMonth.start, previousMonth.end)
  ];
}

function period(
  code: QuickPeriodCode,
  label: string,
  start: string,
  end: string,
  disabled = false
): QuickPeriod {
  return { code, label, range: { start, end }, disabled };
}
