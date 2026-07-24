import { z } from "zod";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./client";

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers }
  });
}

describe("ApiClient", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=csrf-value; Path=/";
  });

  it("sends credentials and the current CSRF cookie for mutations", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ headerName: "X-XSRF-TOKEN", cookieName: "XSRF-TOKEN" }))
      .mockResolvedValueOnce(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new ApiClient();

    await client.request("/api/example", {
      method: "POST",
      body: { value: 1 },
      schema: z.object({ ok: z.boolean() })
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [, request] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(request.credentials).toBe("include");
    expect(new Headers(request.headers).get("X-XSRF-TOKEN")).toBe("csrf-value");
  });

  it("rejects a successful response that violates the declared contract", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({ value: "wrong" })));
    const client = new ApiClient();

    await expect(client.request("/api/example", {
      schema: z.object({ value: z.number() })
    })).rejects.toMatchObject({ code: "CONTRACT_MISMATCH" });
  });

  it("does not expose non-JSON proxy errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>proxy detail</html>", {
      status: 502,
      headers: { "Content-Type": "text/html" }
    })));
    const client = new ApiClient();

    await expect(client.request("/api/example")).rejects.toMatchObject({
      status: 502,
      message: "Сервис временно недоступен. Попробуйте позже."
    });
  });
});
