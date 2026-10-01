import { z } from "zod";
import { apiClient } from "../api/client";

const caseSchema = z.object({
  id: z.string().uuid(), productId: z.string().uuid(), productCode: z.string().nullable(),
  name: z.string(), documentId: z.string().uuid(), documentNumber: z.string().nullable(),
  businessDate: z.string(), quantity: z.number(),
  proposedTarget: z.enum(["IPHONE", "SAMSUNG", "CONFLICT", "NONE"]),
  hasIphone: z.boolean(), hasSamsung: z.boolean(),
  decisionTarget: z.string().nullable(), decisionCurrent: z.boolean(),
  revision: z.number(), fingerprint: z.string(),
  categoryCode: z.enum(["OTHER_CASE", "CASE_UNIVERSAL", "GLASS_PHONE_UNRESOLVED", "PROTECTIVE_FILM",
    "CHARGER_CABLE", "ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH"]),
  allowedTargets: z.array(z.enum(["CASE_APPLE_IPHONE", "CASE_SAMSUNG", "CASE_OTHER_DEVICE",
    "GLASS_IPHONE", "GLASS_SAMSUNG", "GLASS_OTHER", "FILM_PHONE", "FILM_NON_PHONE", "DEFER",
    "CHARGER_CABLE", "ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH", "NO_ATTACH"])).min(1)
});
const queueSchema = z.object({
  items: z.array(caseSchema), total: z.number(), openCount: z.number(),
  conflictCount: z.number(), openQuantity: z.number(), offset: z.number(), limit: z.number()
});
const detailSchema = z.object({
  item: caseSchema,
  history: z.array(z.object({
    id: z.string().uuid(), revision: z.number(), targetCode: z.string(),
    reason: z.string(), actorId: z.string().uuid().nullable(), createdAt: z.string()
  })),
  affectedDates: z.array(z.string())
});
const previewSchema = z.object({
  previousTarget: z.string().nullable(), nextTarget: z.string(), netQuantity: z.number(),
  affectedDates: z.array(z.string()), warnings: z.array(z.string())
});
const estimatesSchema = z.object({
  periodStart: z.string(), periodEnd: z.string(), conflictCount: z.number(), unresolvedCount: z.number(),
  unresolvedReturnCount: z.number(),
  rates: z.array(z.object({
    metricCode: z.string(), confirmedQuantity: z.number(), inferredQuantity: z.number(),
    denominatorQuantity: z.number(), confirmedRatePerHundred: z.number().nullable(),
    indicativeRatePerHundred: z.number().nullable()
  }))
});

export type CaseAttachItem = z.infer<typeof caseSchema>;
export type CaseAttachDetail = z.infer<typeof detailSchema>;
export type CaseAttachDecision = { targetCode: "CASE_APPLE_IPHONE" | "CASE_SAMSUNG" | "CASE_OTHER_DEVICE" | "GLASS_IPHONE" | "GLASS_SAMSUNG" | "GLASS_OTHER" | "FILM_PHONE" | "FILM_NON_PHONE" | "DEFER" | "CHARGER_CABLE" | "ACCESSORY_AIRPODS" | "ACCESSORY_APPLE_WATCH" | "NO_ATTACH"; reason: string };
const base = (storeId: string) => `/api/stores/${storeId}/attach-rate/cases`;
export const caseKeys = {
  queue: (storeId: string, state: string, offset: number) => ["stores", storeId, "case-review", state, offset] as const,
  detail: (storeId: string, id: string) => ["stores", storeId, "case-review-detail", id] as const,
  estimates: (storeId: string, start: string, end: string) => ["stores", storeId, "case-estimates", start, end] as const
};
export const getCaseQueue = (storeId: string, state = "OPEN", offset = 0) =>
  apiClient.request(`${base(storeId)}?state=${state}&offset=${offset}&limit=30`, { schema: queueSchema });
export const getCaseDetail = (storeId: string, id: string) =>
  apiClient.requestEtagged(`${base(storeId)}/${id}`, { schema: detailSchema });
export const previewCaseDecision = (storeId: string, id: string, body: CaseAttachDecision) =>
  apiClient.request(`${base(storeId)}/${id}/preview`, { method: "POST", body, schema: previewSchema });
export const saveCaseDecision = (storeId: string, id: string, etag: string, body: CaseAttachDecision) =>
  apiClient.requestEtagged(`${base(storeId)}/${id}/decisions`, {
    method: "POST", body, schema: detailSchema, headers: { "If-Match": etag },
    idempotencyScope: `case:${storeId}:${id}:${etag}`
  });
export const getCaseEstimates = (storeId: string, start: string, end: string) =>
  apiClient.request(`${base(storeId)}/estimates?periodStart=${start}&periodEnd=${end}`, { schema: estimatesSchema });
