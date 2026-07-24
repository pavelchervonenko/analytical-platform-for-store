export interface PlanFormValues {
  revenueTarget: string;
  accessoryShareTarget: string;
  serviceShareTarget: string;
  additionalShareTarget: string;
}

export interface PlanInput {
  revenueTarget: number;
  accessoryShareTarget: number;
  serviceShareTarget: number;
  additionalShareTarget: number;
}

export type PlanFormErrors = Partial<Record<keyof PlanFormValues, string>>;

function parseDecimal(value: string, minimum: number, maximum: number, integerDigits: number): number | null {
  const normalized = value.trim().replace(/\s|\u00a0/gu, "").replace(",", ".");
  const pattern = new RegExp(`^\\d{1,${integerDigits}}(?:\\.\\d{1,2})?$`, "u");
  if (!pattern.test(normalized)) return null;
  const parsed = Number(normalized);
  return Number.isFinite(parsed) && parsed >= minimum && parsed <= maximum ? parsed : null;
}

export function validatePlanForm(values: PlanFormValues): { data: PlanInput | null; errors: PlanFormErrors } {
  const revenueTarget = parseDecimal(values.revenueTarget, 0.01, Number.MAX_SAFE_INTEGER / 100, 17);
  const accessoryShareTarget = parseDecimal(values.accessoryShareTarget, 0, 100, 3);
  const serviceShareTarget = parseDecimal(values.serviceShareTarget, 0, 100, 3);
  const additionalShareTarget = parseDecimal(values.additionalShareTarget, 0, 100, 3);
  const errors: PlanFormErrors = {};
  if (revenueTarget == null) errors.revenueTarget = "Введите положительную сумму, максимум с двумя знаками после запятой.";
  if (accessoryShareTarget == null) errors.accessoryShareTarget = "Введите долю от 0 до 100% с точностью до двух знаков.";
  if (serviceShareTarget == null) errors.serviceShareTarget = "Введите долю от 0 до 100% с точностью до двух знаков.";
  if (additionalShareTarget == null) errors.additionalShareTarget = "Введите долю от 0 до 100% с точностью до двух знаков.";
  return {
    data: Object.keys(errors).length === 0 ? { revenueTarget: revenueTarget!, accessoryShareTarget: accessoryShareTarget!, serviceShareTarget: serviceShareTarget!, additionalShareTarget: additionalShareTarget! } : null,
    errors
  };
}

export function parseWorkedHours(value: string): number | null {
  return parseDecimal(value, 0.01, 11, 2);
}

export function buildMonthCalendar(month: string): Array<string | null> {
  const match = /^(\d{4})-(\d{2})$/u.exec(month);
  if (!match) throw new Error("Invalid ISO month");
  const year = Number(match[1]);
  const monthIndex = Number(match[2]) - 1;
  if (monthIndex < 0 || monthIndex > 11) throw new Error("Invalid ISO month");
  const firstWeekday = (new Date(Date.UTC(year, monthIndex, 1)).getUTCDay() + 6) % 7;
  const daysInMonth = new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate();
  const cells: Array<string | null> = Array.from({ length: firstWeekday }, () => null);
  for (let day = 1; day <= daysInMonth; day += 1) {
    cells.push(`${month}-${String(day).padStart(2, "0")}`);
  }
  while (cells.length % 7 !== 0) cells.push(null);
  return cells;
}
