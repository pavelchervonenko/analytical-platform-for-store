import { z } from "zod";
import { apiClient } from "../api/client";

const allocation = z.object({
  deviceItemId: z.string().uuid().nullable(), deviceDocumentId: z.string().uuid(),
  deviceType: z.enum(["NEW", "USED"]), businessDate: z.string(),
  employeeId: z.string().uuid().nullable(), quantity: z.number()
});
export const warrantyCaseSchema = z.object({
  id: z.string().uuid(), documentId: z.string().uuid(), documentExternalId: z.string(),
  documentNumber: z.string().nullable(), businessDate: z.string(), documentKind: z.enum(["SALE", "RETURN"]),
  name: z.string(), quantity: z.number(), state: z.string(), conflictCode: z.string().nullable(),
  fingerprint: z.string(), revision: z.number(), originalWarrantyItemId: z.string().uuid().nullable(),
  financialEmployeeName: z.string().nullable().optional()
});
export const warrantyDeviceSchema = z.object({
  id: z.string().uuid(), documentId: z.string().uuid(), documentExternalId: z.string(),
  documentNumber: z.string().nullable(), businessDate: z.string(), employeeId: z.string().uuid().nullable(),
  employeeName: z.string().nullable(), name: z.string(), deviceType: z.enum(["NEW", "USED"]),
  quantity: z.number(), fingerprint: z.string(), allocatedQuantity: z.number(), returnedQuantity: z.number()
});
const queueSchema = z.object({
  items: z.array(warrantyCaseSchema), total: z.number(), documentCount: z.number(), unallocatedQuantity: z.number(),
  offset: z.number(), limit: z.number(), enabled: z.boolean()
});
const detailSchema = z.object({
  warranty: warrantyCaseSchema, candidates: z.array(warrantyDeviceSchema), allocations: z.array(allocation),
  history: z.array(z.object({
    id: z.string().uuid(), revision: z.number(), action: z.string(), actorId: z.string().uuid(),
    actorName: z.string(), reason: z.string(), createdAt: z.string(), allocations: z.array(allocation)
  })), warnings: z.array(z.string())
});
const previewSchema = z.object({
  sourceItemId: z.string().uuid(), action: z.string(), allocations: z.array(allocation),
  affectedDates: z.array(z.string()), warnings: z.array(z.string())
});
export type WarrantyCase = z.infer<typeof warrantyCaseSchema>;
export type WarrantyDevice = z.infer<typeof warrantyDeviceSchema>;
export type WarrantyDetail = z.infer<typeof detailSchema>;
export type WarrantyPreview = z.infer<typeof previewSchema>;
export type WarrantyAction = "ALLOCATE" | "EXCLUDE" | "DEFER";
export interface WarrantyDecision {
  action: WarrantyAction;
  reason: string;
  originalWarrantyItemId: string | null;
  allocations: { deviceItemId: string; quantity: number; fingerprint: string }[];
}
const base = (storeId: string) => `/api/stores/${storeId}/attach-rate/warranties`;
export const warrantyKeys = {
  queue: (storeId: string, state: string, offset: number) => ["stores", storeId, "warranties", state, offset] as const,
  detail: (storeId: string, sourceId: string) => ["stores", storeId, "warranty", sourceId] as const
};
export const getWarrantyQueue = (storeId: string, state = "OPEN", offset = 0) =>
  apiClient.request(`${base(storeId)}?state=${state}&offset=${offset}&limit=30`, { schema: queueSchema });
export const getWarrantyDetail = (storeId: string, id: string) =>
  apiClient.requestEtagged(`${base(storeId)}/${id}`, { schema: detailSchema });
export const searchWarrantyDevices = (storeId: string, query: string) =>
  apiClient.request(`${base(storeId)}/devices?query=${encodeURIComponent(query)}`, { schema: z.array(warrantyDeviceSchema) });
export const searchOriginalWarranties = (storeId: string, query: string) =>
  apiClient.request(`${base(storeId)}/originals?query=${encodeURIComponent(query)}`, { schema: z.array(warrantyCaseSchema) });
export const previewWarrantyDecision = (storeId: string, id: string, body: WarrantyDecision) =>
  apiClient.request(`${base(storeId)}/${id}/preview`, { method: "POST", body, schema: previewSchema });
export const saveWarrantyDecision = (storeId: string, id: string, etag: string, body: WarrantyDecision) =>
  apiClient.requestEtagged(`${base(storeId)}/${id}/decisions`, {
    method: "POST", body, schema: detailSchema, headers: { "If-Match": etag },
    idempotencyScope: `warranty:${storeId}:${id}:${etag}`
  });
