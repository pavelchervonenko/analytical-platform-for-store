const issueMessages: Readonly<Record<string, string>> = {
  SOURCE_SYNC_IN_PROGRESS: "Синхронизация данных выполняется",
  SOURCE_DATA_NOT_SYNCED: "Продажи и возвраты еще не синхронизированы",
  SOURCE_DATA_STALE: "Продажи или возвраты отстают от ожидаемой даты",
  SOURCE_SYNC_FAILED: "Последняя синхронизация завершилась с ошибкой",
  SOURCE_DATA_INCOMPLETE_THROUGH_AS_OF: "Исходные данные не охватывают выбранную дату",
  SOURCE_PRODUCTS_UNMAPPED: "Часть товарных позиций не классифицирована",
  SOURCE_COST_DATA_MISSING: "Для части товарных позиций отсутствует себестоимость",
  SOURCE_COST_DATA_ZERO_UNEXPECTED: "У части товарных позиций неожиданно указана нулевая себестоимость",
  SOURCE_OPEN_QUALITY_ISSUES: "В магазине есть открытые проблемы согласованности данных",
  STORE_PLAN_MISSING: "Для выбранного месяца не задан план магазина",
  RATING_PLAN_COVERAGE_INCOMPLETE: "Период рейтинга не полностью покрыт планами магазина",
  RATING_INPUT_DATA_INCOMPLETE: "Недостаточно исходных данных для достоверного рейтинга на выбранную дату",
  RATING_NO_ELIGIBLE_EMPLOYEES: "Нет сотрудников, допущенных к участию в рейтинге",
  RATING_NO_EMPLOYEES_WITH_SHIFTS: "Для периода рейтинга не заполнены смены сотрудников",
  RATING_SALES_WITHOUT_SHIFT: "У части сотрудников есть продажи, но отсутствуют отработанные смены",
  RATING_NO_RANKED_EMPLOYEES: "Ни у одного сотрудника недостаточно данных для определения места в рейтинге",
  RATING_SCORE_COVERAGE_INSUFFICIENT: "Часть сотрудников не достигла минимального покрытия показателей",
  RATING_HISTORY_NOT_FINALIZED: "Завершенный период рейтинга еще не зафиксирован",
  PAYROLL_PLAN_MISSING: "Невозможно рассчитать зарплату без плана магазина",
  PAYROLL_SCHEME_MISSING: "Для выбранного месяца не задана формула расчета зарплаты",
  PAYROLL_PRODUCTS_UNMAPPED: "В расчете зарплаты есть неклассифицированные товарные позиции",
  PAYROLL_REQUIRED_COST_MISSING: "Для расчета зарплаты не хватает данных о себестоимости",
  PAYROLL_DAYS_WITHOUT_SHIFT: "Для части дней зарплатного фонда не заполнены смены сотрудников",
  PAYROLL_PERIOD_DATA_INCOMPLETE: "Исходные данные не охватывают завершенный месяц расчета зарплаты",
  PAYROLL_NOT_CALCULATED: "Зарплата за выбранный месяц еще не рассчитана",
  PAYROLL_RECALCULATION_REQUIRED: "Последний расчет зарплаты устарел и требует пересчета",
  SYNC_IN_PROGRESS: "Синхронизация данных выполняется",
  DATA_NOT_SYNCED: "Продажи и возвраты еще не синхронизированы",
  DATA_STALE: "Продажи или возвраты отстают от ожидаемой даты",
  SYNC_FAILED: "Последняя синхронизация завершилась с ошибкой",
  UNMAPPED_PRODUCT: "Для товара не задана аналитическая категория",
  ZERO_UNEXPECTED_COST: "У товара, не являющегося услугой, указана нулевая себестоимость",
  MISSING_COST: "Для проданного товара отсутствует себестоимость",
  SALE_ITEM_GROSS_MISMATCH: "Сумма продажи до скидок не совпадает с суммой активных позиций",
  SALE_ITEM_NET_MISMATCH: "Итоговая сумма продажи не совпадает с суммой активных позиций",
  SALE_ITEM_COST_MISMATCH: "Себестоимость продажи не совпадает с себестоимостью активных позиций",
  SALE_PAYMENT_MISMATCH: "Сумма оплат не совпадает с итоговой суммой продажи",
  RETURN_ORIGINAL_DOCUMENT_MISSING: "Возврат не удалось связать с исходной продажей",
  RETURN_ORIGINAL_ITEM_MISSING: "Позицию возврата не удалось связать с позицией исходной продажи",
  RETURN_ZERO_UNEXPECTED_COST: "У возвращенного товара, не являющегося услугой, указана нулевая себестоимость",
  RETURN_MISSING_COST: "Для возвращенного товара отсутствует себестоимость",
  RETURN_PAYMENT_MISMATCH: "Сумма возврата не совпадает с суммой возвращенных позиций",
  RETURN_CASH_TRANSACTION_MISMATCH: "Кассовые операции возврата не совпадают с оплатами документа"
};

const sourceLabels: Readonly<Record<string, string>> = {
  SYNCHRONIZATION: "Синхронизация",
  SALES: "Продажи",
  RETURNS: "Возвраты",
  DATA: "Данные"
};

const areaLabels: Readonly<Record<string, string>> = {
  SOURCE_DATA: "Исходные данные",
  STORE_PLAN: "План магазина",
  EMPLOYEE_RATING: "Рейтинг сотрудников",
  PAYROLL: "Расчет зарплаты"
};

const statusLabels: Readonly<Record<string, string>> = {
  OK: "Готово",
  WARNING: "Нужно внимание",
  ERROR: "Есть блокирующие проблемы"
};

const severityLabels: Readonly<Record<string, string>> = {
  ERROR: "Ошибка",
  WARNING: "Предупреждение",
  INFO: "Информация"
};

export function qualityIssueMessage(code: string): string {
  return issueMessages[code] ?? "Обнаружена проблема качества данных. Требуется проверка.";
}

export function qualitySourceLabel(source: string): string {
  return sourceLabels[source] ?? "Данные";
}

export function qualityAreaLabel(area: string): string {
  return areaLabels[area] ?? "Другое направление";
}

export function qualityStatusLabel(status: string): string {
  return statusLabels[status] ?? "Статус неизвестен";
}

export function qualitySeverityLabel(severity: string): string {
  return severityLabels[severity] ?? "Статус неизвестен";
}
