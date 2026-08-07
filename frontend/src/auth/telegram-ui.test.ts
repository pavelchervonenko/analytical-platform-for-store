import { describe, expect, it } from "vitest";
import { ApiClientError } from "../api/client";
import {
  formatQuietHours,
  telegramApiTime,
  telegramMutationError,
  telegramPollInterval,
  telegramTimeInput
} from "./telegram-ui";

describe("Telegram profile UI policy", () => {
  it("polls only while an external Telegram action can change the state", () => {
    expect(telegramPollInterval("LINK_ISSUED")).toBe(2_000);
    expect(telegramPollInterval("PENDING_CONFIRMATION")).toBe(3_000);
    expect(telegramPollInterval("BOT_BLOCKED")).toBe(10_000);
    expect(telegramPollInterval("ACTIVE")).toBe(false);
    expect(telegramPollInterval("NOT_LINKED")).toBe(false);
    expect(telegramPollInterval("UNKNOWN")).toBe(false);
  });

  it("turns stale ETag failures into a safe refresh instruction", () => {
    expect(telegramMutationError(new ApiClientError("stale", {
      status: 412,
      code: "PRECONDITION_FAILED"
    }))).toContain("Состояние подключения уже изменилось");
  });

  it("renders quiet hours without provider-level seconds", () => {
    expect(formatQuietHours("21:00:00", "08:00:00")).toBe("21:00–08:00");
  });

  it("converts delivery times without silently accepting invalid values", () => {
    expect(telegramTimeInput("21:30:00")).toBe("21:30");
    expect(telegramApiTime("07:15")).toBe("07:15:00");
    expect(telegramApiTime("24:00")).toBeNull();
    expect(telegramApiTime("7:15")).toBeNull();
  });
});
