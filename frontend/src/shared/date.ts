const ISO_MONTH = /^(\d{4})-(\d{2})$/u;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/u;

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

export function currentDateInTimeZone(timeZone: string, now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(now);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${values.year}-${values.month}-${values.day}`;
}

export function monthFromDate(date: string): string {
  return date.slice(0, 7);
}

export function monthRange(month: string): { start: string; end: string } {
  const match = ISO_MONTH.exec(month);
  if (!match) throw new Error("Invalid ISO month");
  const year = Number(match[1]);
  const monthNumber = Number(match[2]);
  if (monthNumber < 1 || monthNumber > 12) throw new Error("Invalid ISO month");
  const lastDay = new Date(Date.UTC(year, monthNumber, 0)).getUTCDate();
  return { start: `${year}-${pad(monthNumber)}-01`, end: `${year}-${pad(monthNumber)}-${pad(lastDay)}` };
}

export function isIsoDate(value: string | null): value is string {
  if (!value || !ISO_DATE.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.valueOf()) && date.toISOString().slice(0, 10) === value;
}

export function shiftDate(date: string, days: number): string {
  if (!isIsoDate(date)) throw new Error("Invalid ISO date");
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

export function weekRange(date: string): { start: string; end: string } {
  if (!isIsoDate(date)) throw new Error("Invalid ISO date");
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  const start = shiftDate(date, -(day === 0 ? 6 : day - 1));
  return { start, end: shiftDate(start, 6) };
}

export function inclusiveDayCount(start: string, end: string): number {
  if (!isIsoDate(start) || !isIsoDate(end) || end < start) return 0;
  return Math.round((Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / 86_400_000) + 1;
}

export function clampAsOfDate(today: string, start: string, end: string): string {
  if (today < start) return start;
  if (today > end) return end;
  return today;
}

export function clampCompletedAsOfDate(today: string, start: string, end: string): string {
  return clampAsOfDate(shiftDate(today, -1), start, end);
}

export function shiftMonth(month: string, offset: number): string {
  const match = ISO_MONTH.exec(month);
  if (!match) throw new Error("Invalid ISO month");
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1 + offset, 1));
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}`;
}

export function formatMonth(month: string): string {
  const { start } = monthRange(month);
  return new Intl.DateTimeFormat("ru-RU", { month: "long", year: "numeric", timeZone: "UTC" })
    .format(new Date(`${start}T00:00:00Z`));
}

export function formatDate(date: string | null): string {
  if (!date) return "—";
  return new Intl.DateTimeFormat("ru-RU", { day: "2-digit", month: "long", year: "numeric", timeZone: "UTC" })
    .format(new Date(`${date}T00:00:00Z`));
}

export function formatDateShort(date: string): string {
  return new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" })
    .format(new Date(`${date}T00:00:00Z`));
}
