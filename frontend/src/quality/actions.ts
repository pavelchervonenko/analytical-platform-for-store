import type { QualityAction } from "./api";

export interface QualityActionDescriptor {
  label: string;
  route?: string;
  view?: string;
  refresh?: boolean;
  unavailableReason?: string;
}

export function describeQualityAction(action: QualityAction, isAdmin: boolean): QualityActionDescriptor | null {
  switch (action) {
    case "NONE": return null;
    case "WAIT_FOR_SYNC": return { label: "Обновить статус", refresh: true };
    case "RUN_SYNC": return isAdmin
      ? { label: "К синхронизации", route: "/admin", view: "sync" }
      : { label: "Нужен администратор", unavailableReason: "Запуск синхронизации доступен только администратору." };
    case "SET_STORE_PLAN": return { label: "Заполнить план", route: "/plan", view: "plan" };
    case "UPDATE_WORK_SCHEDULE": return { label: "Заполнить смены", route: "/plan", view: "shifts" };
    case "REVIEW_EMPLOYEE_ELIGIBILITY": return { label: "Проверить участников", route: "/employees" };
    case "CLASSIFY_PRODUCTS": return isAdmin
      ? { label: "К классификации", route: "/admin", view: "classification" }
      : { label: "Нужен администратор", unavailableReason: "Классификацию товаров выполняет администратор." };
    case "PROVIDE_COST_DATA": return { label: "Проверить себестоимость", unavailableReason: "Ручное исправление себестоимости пока не поддерживается API. Проверьте источник данных и синхронизацию." };
    case "CALCULATE_PAYROLL": return { label: "Рассчитать зарплату", route: "/payroll" };
    case "RECALCULATE_PAYROLL": return { label: "Пересчитать", route: "/payroll" };
    case "FINALIZE_RATING": return { label: "Зафиксировать рейтинг", route: "/employees" };
    case "REVIEW_DATA_ISSUES":
    case "REVIEW_SOURCE_DOCUMENT": return { label: "Открыть проблемы", route: "/quality", view: "issues" };
    default: return null;
  }
}
