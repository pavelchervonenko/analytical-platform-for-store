import { afterEach, describe, expect, it, vi } from "vitest";
import type { PerformancePlan } from "./contracts";
import { apiClient } from "./client";
import {
  replaceWorkScheduleDay,
  upsertPerformancePlan
} from "./queries";

const storeId = "30df06fb-71fe-4477-b6b9-bbc712b1ab25";

describe("optimistic concurrency request contract", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("uses If-None-Match only for first plan creation", async () => {
    const request = vi.spyOn(apiClient, "requestEtagged").mockResolvedValue({
      value: {} as never,
      etag: '"next"'
    });

    await upsertPerformancePlan(storeId, "2026-07", planInput(), null);

    expect(request).toHaveBeenCalledWith(
      `/api/stores/${storeId}/performance-plans/2026-07`,
      expect.objectContaining({
        method: "PUT",
        headers: { "If-None-Match": "*" }
      })
    );
  });

  it("forwards the current strong plan ETag returned by the server", async () => {
    const request = vi.spyOn(apiClient, "requestEtagged").mockResolvedValue({
      value: {} as never,
      etag: '"next"'
    });
    const current = {
      id: "30df06fb-71fe-4477-b6b9-bbc712b1ab26",
      storeId,
      planMonth: "2026-07-01",
      ...planInput(),
      updatedBy: "30df06fb-71fe-4477-b6b9-bbc712b1ab27",
      version: 4,
      updatedAt: "2026-07-27T10:00:00Z"
    } satisfies PerformancePlan;

    await upsertPerformancePlan(storeId, "2026-07", planInput(), { value: current, etag: '"opaque-plan-version"' });

    expect(request).toHaveBeenCalledWith(
      `/api/stores/${storeId}/performance-plans/2026-07`,
      expect.objectContaining({
        headers: {
          "If-Match": '"opaque-plan-version"'
        }
      })
    );
  });

  it("forwards the opaque aggregate day ETag returned by the server", async () => {
    const request = vi.spyOn(apiClient, "requestEtagged").mockResolvedValue({
      value: {} as never,
      etag: '"next"'
    });

    await replaceWorkScheduleDay(storeId, "2026-07-21", '"opaque-day-version"', []);

    expect(request).toHaveBeenCalledWith(
      `/api/stores/${storeId}/work-schedule/2026-07-21`,
      expect.objectContaining({
        method: "PUT",
        headers: {
          "If-Match": '"opaque-day-version"'
        },
        body: { shifts: [] }
      })
    );
  });
});

function planInput() {
  return {
    revenueTarget: 24000000,
    accessoryShareTarget: 3.9,
    serviceShareTarget: 3,
    additionalShareTarget: 7
  };
}
