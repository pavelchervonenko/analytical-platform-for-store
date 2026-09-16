import { z } from "zod";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./client";

function response(etag?: string): Response {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (etag) headers.ETag = etag;
  return new Response(JSON.stringify({ id: "resource" }), { status: 200, headers });
}

describe("ApiClient ETag resources", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("keeps a server ETag opaque", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response('"opaque:v9"'));
    vi.stubGlobal("fetch", fetchMock);

    await expect(new ApiClient().requestEtagged("/api/resource", {
      schema: z.object({ id: z.string() })
    })).resolves.toEqual({ value: { id: "resource" }, etag: '"opaque:v9"' });

    const requestHeaders = new Headers(fetchMock.mock.calls[0]?.[1]?.headers);
    expect(requestHeaders.get("Cache-Control")).toBe("no-transform");
  });

  it("preserves existing cache directives while forbidding ETag transformations", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response('"opaque:v10"'));
    vi.stubGlobal("fetch", fetchMock);

    await new ApiClient().requestWithOptionalEtag("/api/resource", {
      headers: { "Cache-Control": "max-age=0" },
      schema: z.object({ id: z.string() })
    });

    const requestHeaders = new Headers(fetchMock.mock.calls[0]?.[1]?.headers);
    expect(requestHeaders.get("Cache-Control")).toBe("max-age=0, no-transform");
  });

  it("rejects a versioned resource without a strong ETag", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response()));

    await expect(new ApiClient().requestEtagged("/api/resource", {
      schema: z.object({ id: z.string() })
    })).rejects.toMatchObject({ code: "ETAG_MISSING" });
  });
});
