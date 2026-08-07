import { afterEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "./client";
import {
  confirmTelegramChannel,
  getTelegramChannel,
  revokeTelegramChannel,
  updateTelegramDeliverySettings
} from "./queries";

describe("Telegram channel request contract", () => {
  afterEach(() => vi.restoreAllMocks());

  it("allows an unversioned NOT_LINKED response", async () => {
    const request = vi.spyOn(apiClient, "requestWithOptionalEtag")
      .mockResolvedValue({
        value: { subscriptionId: null } as never,
        etag: null
      });


    await getTelegramChannel();

    expect(request).toHaveBeenCalledWith(
      "/api/notifications/channels/telegram",
      expect.objectContaining({ schema: expect.anything() })
    );
  });

  it("forwards the opaque ETag for confirmation and revocation", async () => {
    const request = vi.spyOn(apiClient, "requestWithOptionalEtag")
      .mockResolvedValue({ value: {} as never, etag: '"next"' });

    await confirmTelegramChannel('"subscription-v4"');
    await revokeTelegramChannel('"subscription-v5"');

    expect(request).toHaveBeenNthCalledWith(1,
      "/api/notifications/channels/telegram/confirm",
      expect.objectContaining({
        method: "POST",
        headers: { "If-Match": '"subscription-v4"' }
      })
    );
    expect(request).toHaveBeenNthCalledWith(2,
      "/api/notifications/channels/telegram/revoke",
      expect.objectContaining({
        method: "POST",
        headers: { "If-Match": '"subscription-v5"' }
      })
    );
  });

  it("sends settings atomically with the current opaque ETag", async () => {
    const request = vi.spyOn(apiClient, "requestWithOptionalEtag")
      .mockResolvedValue({ value: {} as never, etag: '"next"' });
    const input = {
      timezone: "Europe/Moscow",
      quietHoursEnabled: true,
      quietHoursStart: "22:00:00",
      quietHoursEnd: "07:30:00"
    };

    await updateTelegramDeliverySettings({ input, etag: '"subscription-v6"' });

    expect(request).toHaveBeenCalledWith(
      "/api/notifications/channels/telegram/settings",
      expect.objectContaining({
        method: "PUT",
        headers: { "If-Match": '"subscription-v6"' },
        body: input
      })
    );
  });
});
