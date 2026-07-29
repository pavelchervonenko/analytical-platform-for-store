import { describe, expect, it } from "vitest";
import type { ActiveSession } from "../api/contracts";
import { orderActiveSessions } from "./session-ui";

describe("orderActiveSessions", () => {
  it("keeps the current session first and orders the rest by activity", () => {
    const sessions: ActiveSession[] = [
      { sessionReference: "older", lastSeenAt: "2026-07-27T10:00:00Z", current: false },
      { sessionReference: "current", lastSeenAt: "2026-07-27T09:00:00Z", current: true },
      { sessionReference: "newer", lastSeenAt: "2026-07-27T11:00:00Z", current: false }
    ];

    expect(orderActiveSessions(sessions).map((session) => session.sessionReference))
      .toEqual(["current", "newer", "older"]);
    expect(sessions[0]?.sessionReference).toBe("older");
  });
});
