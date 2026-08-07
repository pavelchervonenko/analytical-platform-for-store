import { describe, expect, it } from "vitest";
import { apiErrorMessage } from "./errorMessages";

describe("apiErrorMessage", () => {
  it("localizes stable backend codes", () => {
    expect(apiErrorMessage("INVALID_CREDENTIALS", 401))
      .toBe("Неверная электронная почта или пароль.");
    expect(apiErrorMessage("PAYROLL_SOURCE_DATA_CHANGED", 409))
      .toContain("Пересчитайте зарплату");
  });

  it("does not expose unknown backend messages through forward-compatible codes", () => {
    expect(apiErrorMessage("FUTURE_BACKEND_ERROR", 409))
      .toBe("Данные уже изменились. Обновите страницу и повторите действие.");
  });
});
