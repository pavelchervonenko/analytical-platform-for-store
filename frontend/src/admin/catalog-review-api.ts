import { z } from "zod";
import { apiClient } from "../api/client";
import type { PayrollCategory } from "./api";

const itemSchema = z.object({
  productId: z.string().uuid(), productVersion: z.number().int().nonnegative(),
  externalId: z.string(), code: z.string().nullable(), name: z.string(),
  sourceKind: z.string(), sourceGroupPath: z.string().nullable(),
  firstSaleAt: z.string(), firstSaleDate: z.string(),
  saleItemCount: z.number().int().nonnegative(), hasUnmappedSales: z.boolean(),
  assignedAnalyticsCategoryCode: z.string().nullable(),
  assignedConditionType: z.string().nullable(),
  assignedPayrollCategoryCode: z.string().nullable(),
  suggestedAnalyticsCategoryCode: z.string().nullable()
});
const queueSchema = z.object({
  activationFrom: z.string().nullable(), items: z.array(itemSchema), hasMore: z.boolean()
});
const categorySchema = z.object({
  code: z.string(), name: z.string(), defaultPayrollCategoryCode: z.string()
});
const resultSchema = z.object({
  productId: z.string().uuid(), analyticsCategoryCode: z.string(),
  payrollCategoryCode: z.string(), reclassifiedItems: z.number().int().nonnegative(),
  affectedStoreIds: z.array(z.string().uuid())
});

export type CatalogReviewItem = z.infer<typeof itemSchema>;
export type CatalogReviewCategory = z.infer<typeof categorySchema>;
export type CatalogReviewQueue = z.infer<typeof queueSchema>;
export type CatalogReviewCondition = "NEW" | "ASIS" | "USED" | "NOT_APPLICABLE" | "UNKNOWN";
export interface CatalogReviewDecision {
  expectedProductVersion: number;
  analyticsCategoryCode: string;
  conditionType: CatalogReviewCondition;
  payrollCategoryCode: PayrollCategory;
  reason: string;
}

export const getCatalogReviewQueue = (): Promise<CatalogReviewQueue> =>
  apiClient.request("/api/admin/catalog-product-reviews?limit=100", { schema: queueSchema });
export const getCatalogReviewCategories = (): Promise<CatalogReviewCategory[]> =>
  apiClient.request("/api/admin/catalog-product-reviews/categories", { schema: z.array(categorySchema) });
export const decideCatalogReview = (productId: string, decision: CatalogReviewDecision) =>
  apiClient.request(`/api/admin/catalog-product-reviews/${encodeURIComponent(productId)}/decision`, {
    method: "POST", body: decision, schema: resultSchema
  });
