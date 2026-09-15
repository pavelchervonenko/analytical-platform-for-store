import { describe, expect, it } from "vitest";
import { validateNewPassword } from "./ChangePasswordPage";

describe("new password validation", () => {
  it("explains mismatch and password reuse before sending a request", () => {
    expect(validateNewPassword("temporary pass 2026", "new secure pass 2026", "different"))
      .toContain("не совпадают");
    expect(validateNewPassword("temporary pass 2026", "temporary pass 2026", "temporary pass 2026"))
      .toContain("должен отличаться");
  });

  it("matches the backend UTF-8 byte limit", () => {
    expect(validateNewPassword("old password 2026", "Я".repeat(40), "Я".repeat(40)))
      .toContain("72 байт");
    expect(validateNewPassword("old password 2026", "четыре спокойных слова", "четыре спокойных слова"))
      .toBeNull();
  });
});
