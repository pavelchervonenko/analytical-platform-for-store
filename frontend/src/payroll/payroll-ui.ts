import type { PayrollStatement } from "../api/contracts";

export interface PayrollTotals {
  earned: number;
  advance: number;
  deductions: number;
  payable: number;
}

export function summarizeStatements(statements: PayrollStatement[]): PayrollTotals {
  return statements.reduce<PayrollTotals>((totals, statement) => ({
    earned: totals.earned + statement.earnedAmount,
    advance: totals.advance + statement.advanceAmount,
    deductions: totals.deductions + statement.penaltyAmount + statement.inventoryAmount + statement.taxAmount,
    payable: totals.payable + statement.payableAmount
  }), { earned: 0, advance: 0, deductions: 0, payable: 0 });
}

export function parsePayrollAmount(value: string): number | null {
  const normalized = value.trim().replace(",", ".");
  if (!/^\d+(?:\.\d{1,2})?$/u.test(normalized)) return null;
  const amount = Number(normalized);
  return Number.isSafeInteger(Math.round(amount * 100)) && amount >= 0.01 ? amount : null;
}

export function validateReason(value: string): string | null {
  const reason = value.trim();
  if (!reason) return "Укажите причину — она сохранится в истории.";
  if (reason.length > 500) return "Причина не должна превышать 500 символов.";
  return null;
}

const payrollCategoryLabels: Readonly<Record<string, string>> = {
  TECH_TIER_1: "Техника, уровень 1",
  TECH_TIER_2: "Техника, уровень 2",
  ACCESSORY: "Аксессуар",
  SERVICE: "Услуга",
  PLAYSTATION_SUBSCRIPTION: "Подписка PlayStation",
  PAID_REPAIR: "Платный ремонт",
  EXCLUDE: "Не участвует в расчете зарплаты"
};

const adjustmentLabels: Record<string, string> = {
  PENALTY: "Штраф",
  INVENTORY: "Инвентаризация",
  TAX: "Налог"
};

const staleReasonLabels: Record<string, string> = {
  SALES_DATA_CHANGED: "Изменились продажи или возвраты",
  WORK_SHIFTS_CHANGED: "Изменился состав смен",
  STORE_PLAN_CHANGED: "Изменился план магазина",
  PRODUCT_CLASSIFICATION_CHANGED: "Изменилась классификация товаров",
  PAYROLL_SCHEME_CHANGED: "Изменилась версия формулы",
  SOURCE_FINGERPRINT_MISSING: "Не удалось проверить актуальность исходных данных"
};

const eventLabels: Record<string, string> = {
  CALCULATED: "Расчет создан",
  RECALCULATED: "Черновик пересчитан",
  REVISION_CREATED: "Создана новая версия",
  ADJUSTMENT_ADDED: "Добавлено удержание",
  ADJUSTMENT_VOIDED: "Удержание отменено",
  APPROVED: "Расчет утвержден",
  PAID: "Выплата отмечена"
};

const comparisonReasonLabels: Record<string, string> = {
  SHIFT_CHANGED: "Изменились смены",
  DAILY_ALLOCATION_CHANGED: "Изменилось дневное начисление",
  ADJUSTMENT_CHANGED: "Изменились удержания",
  ADVANCE_CHANGED: "Изменился аванс",
  SALES_RETURNS_OR_CLASSIFICATION_CHANGED: "Изменились продажи, возвраты или классификация",
  PLAN_STATUS_OR_FORMULA_CHANGED: "Изменился статус плана или формула",
  FUND_CHANGED: "Изменился дневной фонд"
};

export function payrollCategoryLabel(value: string): string {
  return payrollCategoryLabels[value] ?? "Другая категория";
}

export function adjustmentTypeLabel(value: string): string {
  return adjustmentLabels[value] ?? "Другое удержание";
}

export function staleReasonLabel(value: string): string {
  return staleReasonLabels[value] ?? "Изменился источник расчета";
}

export function payrollEventLabel(value: string): string {
  return eventLabels[value] ?? "Событие расчета";
}

export function comparisonReasonLabel(value: string): string {
  return comparisonReasonLabels[value] ?? "Изменились данные расчета";
}

export function formatPayrollMoney(value: number | null | undefined, signed = false): string {
  if (value == null) return "—";
  const formatted = new Intl.NumberFormat("ru-RU", {
    style: "currency",
    currency: "RUB",
    minimumFractionDigits: Number.isInteger(value) ? 0 : 2,
    maximumFractionDigits: 2
  }).format(value);
  return signed && value > 0 ? `+${formatted}` : formatted;
}
