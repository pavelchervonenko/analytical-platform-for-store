const codeMessages: Readonly<Record<string, string>> = {
  VALIDATION_ERROR: "Проверьте заполненные поля и повторите действие.",
  MALFORMED_REQUEST: "Не удалось обработать отправленные данные.",
  INVALID_ARGUMENT: "Проверьте указанные значения и повторите действие.",
  MISSING_PARAMETER: "Не заполнены обязательные данные запроса.",
  METHOD_NOT_ALLOWED: "Это действие недоступно.",
  UNSUPPORTED_MEDIA_TYPE: "Формат отправленных данных не поддерживается.",
  PAYLOAD_TOO_LARGE: "Объем отправленных данных превышает допустимый размер.",
  RESOURCE_NOT_FOUND: "Запрошенные данные не найдены.",
  PRECONDITION_REQUIRED: "Сначала загрузите актуальную версию данных.",
  PRECONDITION_FAILED: "Данные уже изменились. Обновите страницу и повторите действие.",
  CONCURRENT_MODIFICATION: "Данные были изменены другим пользователем. Обновите страницу и повторите действие.",
  INTERNAL_ERROR: "Произошла внутренняя ошибка. Повторите действие позже.",
  INVALID_CREDENTIALS: "Неверная электронная почта или пароль.",
  AUTHENTICATION_REQUIRED: "Сессия завершена. Войдите снова.",
  ACCESS_DENIED: "Недостаточно прав для этого действия.",
  SESSION_EXPIRED: "Сессия завершена. Войдите снова.",
  STORE_NOT_FOUND: "Магазин не найден.",
  EMPLOYEE_ASSIGNMENT_NOT_FOUND: "Назначение сотрудника не найдено.",
  PERFORMANCE_PLAN_NOT_FOUND: "План магазина не найден.",
  RATING_PERIOD_NOT_CLOSED: "Период рейтинга еще не завершен.",
  RATING_SCHEME_CONFLICT: "Версия формулы рейтинга пересекается с существующей версией.",
  RATING_SCHEME_NOT_FOUND: "Формула рейтинга не найдена.",
  EMPLOYEE_RATING_CONFLICT: "Настройки рейтинга уже изменились. Обновите данные и повторите действие.",
  PAYROLL_NOT_FOUND: "Расчет зарплаты не найден.",
  PAYROLL_SOURCE_DATA_CHANGED: "Исходные данные изменились. Пересчитайте зарплату и повторите действие.",
  PAYROLL_SCHEME_CONFLICT: "Версия формулы зарплаты пересекается с существующей версией.",
  PAYROLL_STATE_CONFLICT: "Текущее состояние расчета зарплаты не позволяет выполнить это действие.",
  PAYROLL_ADJUSTMENT_NOT_FOUND: "Удержание не найдено.",
  USER_NOT_FOUND: "Пользователь не найден.",
  USER_EMAIL_CONFLICT: "Пользователь с такой электронной почтой уже существует.",
  USER_ADMINISTRATION_CONFLICT: "Текущее состояние пользователя не позволяет выполнить это действие.",
  INVALID_CURRENT_PASSWORD: "Текущий пароль указан неверно.",
  PASSWORD_POLICY_VIOLATION: "Пароль должен содержать не менее 12 символов.",
  LOGIN_THROTTLED: "Слишком много попыток входа. Повторите попытку позже.",
  CURRENT_SESSION_REQUIRES_LOGOUT: "Чтобы завершить текущую сессию, выйдите из системы.",
  ACTIVE_SYNC_JOB_EXISTS: "Для этого подключения уже выполняется синхронизация.",
  SYNC_CLASSIFICATION_REQUIRED: "Сначала импортируйте утвержденную классификацию товаров для выбранного периода.",
  ACTIVE_REPORT_BACKFILL_JOB_EXISTS: "Для этого магазина уже восстанавливаются отчеты.",
  REPORT_BACKFILL_IDEMPOTENCY_CONFLICT: "Повтор запроса относится к другой задаче восстановления отчетов.",
  IDEMPOTENCY_KEY_CONFLICT: "Повтор запроса относится к другой операции.",
  REPORT_BACKFILL_JOB_NOT_FOUND: "Задача восстановления отчетов не найдена.",
  SYNC_JOB_NOT_FOUND: "Задача синхронизации не найдена.",
  STORE_SYNC_FAILED: "Не удалось синхронизировать магазины.",
  EMPLOYEE_SYNC_FAILED: "Не удалось синхронизировать сотрудников.",
  SALES_SYNC_WINDOW_TOO_LARGE: "Выбранный период продаж слишком велик для одного шага синхронизации.",
  SALES_SYNC_FAILED: "Не удалось синхронизировать продажи.",
  RETURN_SYNC_WINDOW_TOO_LARGE: "Выбранный период возвратов слишком велик для одного шага синхронизации.",
  RETURN_SYNC_FAILED: "Не удалось синхронизировать возвраты.",
  REPORT_NOT_FOUND: "Отчет не найден.",
  WEEKLY_INTERPRETATION_NOT_FOUND: "Еженедельный аналитический разбор не найден.",
  LLM_JOB_NOT_FOUND: "Задача аналитического разбора не найдена.",
  LLM_OPERATIONS_CONFLICT: "Текущее состояние аналитического разбора не позволяет выполнить это действие.",
  TELEGRAM_LINK_STATE_CONFLICT: "Текущее состояние подключения Telegram не позволяет выполнить это действие.",
  TELEGRAM_LINK_THROTTLED: "Слишком много запросов на подключение Telegram. Повторите попытку позже.",
  TELEGRAM_DELIVERY_NOT_FOUND: "Доставка уведомления Telegram не найдена.",
  TELEGRAM_DELIVERY_RESEND_CONFLICT: "Уведомление нельзя отправить повторно в текущем состоянии.",
  PRODUCT_NOT_FOUND: "Товар не найден.",
  PRODUCT_CLASSIFICATION_CONFLICT: "Классификация товара уже изменилась. Обновите данные и повторите действие."
};

function statusMessage(status: number): string {
  if (status === 400 || status === 422) return "Проверьте отправленные данные и повторите действие.";
  if (status === 401) return "Сессия завершена. Войдите снова.";
  if (status === 403) return "Недостаточно прав для этого действия.";
  if (status === 404) return "Запрошенные данные не найдены.";
  if (status === 409 || status === 412) return "Данные уже изменились. Обновите страницу и повторите действие.";
  if (status === 413) return "Объем отправленных данных превышает допустимый размер.";
  if (status === 428) return "Сначала загрузите актуальную версию данных.";
  if (status === 429) return "Слишком много запросов. Попробуйте позже.";
  if (status === 502 || status === 503 || status === 504) return "Внешний сервис временно недоступен. Попробуйте позже.";
  if (status >= 500) return "Сервис временно недоступен. Попробуйте позже.";
  return "Не удалось выполнить запрос.";
}

export function apiErrorMessage(code: string | undefined, status: number): string {
  return code ? codeMessages[code] ?? statusMessage(status) : statusMessage(status);
}
