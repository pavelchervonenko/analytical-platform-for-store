import { z } from "zod";

export const UNKNOWN_ENUM_VALUE = "UNKNOWN" as const;

/**
 * Response-only enum parser. Unknown server values are preserved as a safe UI
 * sentinel so a compatible backend rollout cannot make an entire screen fail.
 * Request schemas must continue to use explicit, closed enums.
 */
export function forwardCompatibleEnum<const Values extends readonly [string, ...string[]]>(values: Values) {
  const knownValues = new Set<string>(values);
  return z.string().transform((value): Values[number] | typeof UNKNOWN_ENUM_VALUE => (
    knownValues.has(value) ? value as Values[number] : UNKNOWN_ENUM_VALUE
  ));
}
