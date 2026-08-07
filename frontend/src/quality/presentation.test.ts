import { describe, expect, it } from "vitest";
import {
  qualityAreaLabel,
  qualityIssueMessage,
  qualitySeverityLabel,
  qualitySourceLabel,
  qualityStatusLabel
} from "./presentation";

describe("quality presentation", () => {
  it("localizes period and persisted issue codes", () => {
    expect(qualityIssueMessage("SOURCE_DATA_NOT_SYNCED"))
      .toBe("Продажи и возвраты еще не синхронизированы");
    expect(qualityIssueMessage("SALE_PAYMENT_MISMATCH"))
      .toBe("Сумма оплат не совпадает с итоговой суммой продажи");
  });

  it("uses a safe Russian fallback for forward-compatible values", () => {
    expect(qualityIssueMessage("FUTURE_ISSUE"))
      .toBe("Обнаружена проблема качества данных. Требуется проверка.");
    expect(qualitySourceLabel("FUTURE_SOURCE")).toBe("Данные");
    expect(qualityAreaLabel("FUTURE_AREA")).toBe("Другое направление");
    expect(qualityStatusLabel("FUTURE_STATUS")).toBe("Статус неизвестен");
    expect(qualitySeverityLabel("FUTURE_SEVERITY")).toBe("Статус неизвестен");
  });
});
