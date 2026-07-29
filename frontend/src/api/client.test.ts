import { z } from "zod";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient, normalizeApiBaseUrl } from "./client";

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

  it("accepts only normalized same-origin API prefixes in production", () => {
    expect(normalizeApiBaseUrl(undefined, true)).toBe("");
    expect(normalizeApiBaseUrl("/", true)).toBe("");
    expect(normalizeApiBaseUrl("/backend-api/", true)).toBe("/backend-api");

    for (const unsafe of [
      "https://api.example.com",
      "http://api.example.com",
      "//api.example.com",
      "/\\api",
      "\\api",
      "/api//v1",
      "/api/../admin",
      "/api?target=other",
      "/api#fragment",
      "api"
    ]) {
      expect(() => normalizeApiBaseUrl(unsafe, true)).toThrow(
        "normalized same-origin path prefix"
      );
    }
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

  it("reuses an idempotency key after network failure and rotates after success", async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError("network unavailable"))
      .mockResolvedValueOnce(jsonResponse({ ok: true }))
      .mockResolvedValueOnce(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new ApiClient();
    const options = {
      method: "POST",
      body: { version: 4 },
      idempotencyScope: "payroll:approve:store:run",
      schema: z.object({ ok: z.boolean() }),
      skipCsrf: true
    } as const;

    await expect(client.request("/api/payroll/approve", options))
      .rejects.toMatchObject({ code: "NETWORK_ERROR" });
    await client.request("/api/payroll/approve", options);
    await client.request("/api/payroll/approve", options);

    const keys = fetchMock.mock.calls.map((call) => {
      const request = call[1] as RequestInit;
      return new Headers(request.headers).get("Idempotency-Key");
    });
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/u);
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[1]);
  });

  it("preserves the backend correlation ID for support diagnostics", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({
      timestamp: "2026-07-24T10:00:00Z",
      status: 500,
      code: "INTERNAL_ERROR",
      message: "An unexpected error occurred",
      path: "/api/example",
      correlationId: "request-42"
    }, {
      status: 500,
      headers: {
        "Content-Type": "application/json",
        "X-Correlation-ID": "request-42"
      }
    })));
    const client = new ApiClient();

    await expect(client.request("/api/example")).rejects.toMatchObject({
      status: 500,
      code: "INTERNAL_ERROR",
      correlationId: "request-42"
    });
  });
});
