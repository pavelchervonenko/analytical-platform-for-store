import openApiDocument from "../../../contracts/openapi/current.json";
import { describe, expect, it } from "vitest";

interface OpenApiDocument {
  paths: Record<string, Record<string, unknown>>;
}

const openApi = openApiDocument as unknown as OpenApiDocument;

describe("LLM frontend consumer contract", () => {
  it.each([
    ["GET", "/api/stores/{storeId}/insights/weekly/current"],
    ["GET", "/api/admin/llm/operations"],
    ["POST", "/api/admin/llm/snapshots/{snapshotId}/regenerate"],
    ["POST", "/api/admin/llm/jobs/{jobId}/cancel"]
  ])("keeps %s %s in the published contract", (method, path) => {
    expect(openApi.paths[path]?.[method.toLowerCase()]).toBeDefined();
  });
});
