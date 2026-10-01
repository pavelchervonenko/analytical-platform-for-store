import type { WarrantyAction, WarrantyCase } from "./api";

export const warrantyReasons: Record<string, string> = {
  MIXED_DEVICE_TYPES: "В документе новые и Б/У устройства — выберите, для каких продана гарантия.",
  UNSUPPORTED_DEVICE: "Тип устройства не подходит для обычной гарантии. Найдите подходящую продажу или исключите гарантию с причиной.",
  DEVICE_SALE_NOT_FOUND: "Продажа подходящего устройства не найдена. Найдите исходную продажу и подтвердите связь.",
  ORIGINAL_WARRANTY_NOT_FOUND: "Не найдена исходная гарантия. Сначала найдите её продажу.",
  RETURN_ALLOCATION_REQUIRED: "Нужно определить, какая гарантия возвращена. Если исходная продажа не распределена, сначала разберите её.",
  SOURCE_CHANGED: "Исходный документ или связанное устройство изменились. Проверьте прежнее решение заново.",
  DEFER: "Решение отложено. Гарантия пока не участвует в attach-rate.",
  EXCLUDE: "Гарантия исключена из attach-rate. Причина сохранена в истории."
};
export const warrantyActions: Record<WarrantyAction, string> = {
  ALLOCATE: "Связать с устройством", EXCLUDE: "Исключить из показателя", DEFER: "Отложить до уточнения"
};
export function warrantyStatus(item: WarrantyCase): string {
  return ({ CONFLICT: "Связь не определена", DEFERRED: "Отложено", EXCLUDED: "Исключено",
    RESOLVED_AUTO: "Определено автоматически", RESOLVED_MANUAL: "Решение сохранено" })[item.state] ?? item.state;
}
export function allocationTotal(quantities: Record<string, string>): number {
  return Math.round(Object.values(quantities).reduce((sum, value) => sum + (Number(value) || 0), 0) * 1000) / 1000;
}
