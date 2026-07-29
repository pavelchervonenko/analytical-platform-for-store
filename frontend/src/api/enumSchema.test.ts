import { describe, expect, it } from "vitest";
import { forwardCompatibleEnum } from "./enumSchema";

describe("forwardCompatibleEnum", () => {
  const schema = forwardCompatibleEnum(["ACTIVE", "DISABLED"]);

  it("preserves known response values", () => {
    expect(schema.parse("ACTIVE")).toBe("ACTIVE");
  });

  it("maps a future response value to UNKNOWN", () => {
    expect(schema.parse("ARCHIVED_BY_SERVER")).toBe("UNKNOWN");
  });

  it("still rejects a non-string payload", () => {
    expect(schema.safeParse(42).success).toBe(false);
  });
});
