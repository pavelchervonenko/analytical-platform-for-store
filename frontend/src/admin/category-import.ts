import { z } from "zod";
import type { ProductCategoryImportItem } from "./api";

const MAX_ASSIGNMENTS = 10_000;
const itemSchema = z.strictObject({
  externalProductId: z.string().trim().min(1, "externalProductId обязателен"),
  productName: z.string().trim().min(1, "productName обязателен"),
  categoryCode: z.string().trim().min(1, "categoryCode обязателен"),
  conditionType: z.enum(["NEW", "ASIS", "USED", "NOT_APPLICABLE", "UNKNOWN"])
});
const assignmentsSchema = z.array(itemSchema).min(1).max(MAX_ASSIGNMENTS);
const artifactSchema = z.strictObject({
  validFrom: z.string().refine((value) => !Number.isNaN(Date.parse(value)), "validFrom должен содержать ISO-дату"),
  ruleVersion: z.string().trim().min(1, "ruleVersion обязателен"),
  changeReason: z.string().trim().optional(),
  assignments: assignmentsSchema
});

export interface CategoryImportMetadata {
  validFrom: string;
  ruleVersion: string;
  changeReason?: string;
}

export type ImportParseResult =
  | { ok: true; assignments: ProductCategoryImportItem[]; metadata?: CategoryImportMetadata }
  | { ok: false; message: string };

function issueMessage(error: z.ZodError): string {
  const issue = error.issues[0];
  if (!issue) return "Проверьте структуру JSON.";
  const location = issue.path.length > 0 ? `Строка ${issue.path.join(".")}: ` : "";
  return `${location}${issue.message}`;
}

export function parseCategoryAssignments(source: string): ImportParseResult {
  let value: unknown;
  try {
    value = JSON.parse(source);
  } catch {
    return { ok: false, message: "JSON содержит синтаксическую ошибку." };
  }

  const parsed = Array.isArray(value)
    ? assignmentsSchema.safeParse(value)
    : artifactSchema.safeParse(value);
  if (!parsed.success) return { ok: false, message: issueMessage(parsed.error) };
  const assignments = Array.isArray(parsed.data) ? parsed.data : parsed.data.assignments;
  const metadata = Array.isArray(parsed.data) ? undefined : {
    validFrom: parsed.data.validFrom,
    ruleVersion: parsed.data.ruleVersion,
    changeReason: parsed.data.changeReason
  };

  const seen = new Set<string>();
  for (const [index, item] of assignments.entries()) {
    if (seen.has(item.externalProductId)) {
      return { ok: false, message: `Строка ${index}: externalProductId «${item.externalProductId}» повторяется.` };
    }
    seen.add(item.externalProductId);
  }
  return { ok: true, assignments, metadata };
}

export function reportingDateTimeToInstant(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/u.test(value)) return null;
  const instant = new Date(`${value}:00+02:00`);
  return Number.isNaN(instant.valueOf()) ? null : instant.toISOString();
}

export function instantToReportingDateTime(value: string): string | null {
  const instant = new Date(value);
  if (Number.isNaN(instant.valueOf())) return null;
  return new Date(instant.valueOf() + 2 * 60 * 60 * 1_000)
    .toISOString()
    .slice(0, 16);
}
