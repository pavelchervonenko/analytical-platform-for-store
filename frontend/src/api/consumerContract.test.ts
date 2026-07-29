import openApiDocument from "../../../contracts/openapi/current.json";
import { describe, expect, it } from "vitest";
import { currentUserSchema, reportSummarySchema, systemStatusSchema } from "./contracts";

interface OpenApiDocument {
  info: { version: string };
  paths: Record<string, Record<string, unknown>>;
  components: { schemas: Record<string, { properties?: Record<string, unknown> }> };
}

const openApi = openApiDocument as unknown as OpenApiDocument;

describe("frontend consumer contract", () => {
  it.each([
    ["GET", "/api/system/status"],
    ["GET", "/api/auth/me"],
    ["GET", "/api/auth/sessions"],
    ["DELETE", "/api/auth/sessions/{sessionReference}"],
    ["DELETE", "/api/auth/sessions/others"],
    ["POST", "/api/sync/jobs/backfill"],
    ["GET", "/api/stores/{storeId}/reports"],
    ["GET", "/api/stores/{storeId}/reports/years"],
    ["GET", "/api/stores/{storeId}/reports/{reportId}"]
  ])("keeps %s %s in the published contract", (method, path) => {
    expect(openApi.paths[path]?.[method.toLowerCase()]).toBeDefined();
  });

  it.each([
    "/api/sync/stores",
    "/api/sync/employees",
    "/api/sync/sales",
    "/api/sync/returns"
  ])("does not publish the retired synchronous endpoint %s", (path) => {
    expect(openApi.paths[path]).toBeUndefined();
  });

  it("publishes the same contract version exposed by system status", () => {
    const status = systemStatusSchema.parse({
      application: "store-analytics",
      version: "1.2.3",
      apiContractVersion: openApi.info.version,
      time: "2026-07-26T12:00:00Z"
    });
    expect(status.apiContractVersion).toBe("9");
    expect(openApi.components.schemas.SystemStatusView?.properties?.apiContractVersion).toBeDefined();
  });

  it("degrades future response enums without granting known semantics", () => {
    const user = currentUserSchema.parse({
      id: "30df06fb-71fe-4477-b6b9-bbc712b1ab25",
      email: "manager@example.com",
      displayName: "Manager",
      role: "AUDITOR",
      passwordChangeRequired: false,
      allStores: false,
      storeIds: []
    });
    const report = reportSummarySchema.parse({
      id: "30df06fb-71fe-4477-b6b9-bbc712b1ab26",
      storeId: "30df06fb-71fe-4477-b6b9-bbc712b1ab27",
      type: "QUARTERLY",
      periodStart: "2026-01-01",
      periodEnd: "2026-03-31",
      coverage: "NEW_COVERAGE_MODE",
      status: "ARCHIVED",
      revision: 1,
      currentRevision: true,
      supersedesReportId: null,
      revisionReason: null,
      payrollRunId: null,
      templateVersion: "1",
      schemaVersion: 1,
      finalizedAt: "2026-04-01T00:00:00Z",
      finalizedBy: null
    });
    expect(user.role).toBe("UNKNOWN");
    expect(report.type).toBe("UNKNOWN");
    expect(report.coverage).toBe("UNKNOWN");
    expect(report.status).toBe("UNKNOWN");
  });
});
