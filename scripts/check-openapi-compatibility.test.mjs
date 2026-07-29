import assert from "node:assert/strict";
import test from "node:test";
import { findBreakingChanges, verifyContracts } from "./check-openapi-compatibility.mjs";

function document(schema, parameters = []) {
  return {
    openapi: "3.0.1",
    info: { title: "test", version: "1" },
    paths: {
      "/widgets": {
        get: {
          parameters,
          responses: {
            200: { content: { "application/json": { schema: { $ref: "#/components/schemas/Widget" } } } }
          }
        }
      }
    },
    components: { schemas: { Widget: schema } }
  };
}

const baseline = document({
  type: "object",
  required: ["id", "status"],
  properties: {
    id: { type: "string", format: "uuid" },
    status: { type: "string", enum: ["ACTIVE", "DISABLED"] }
  }
});

test("allows additive optional fields", () => {
  const current = structuredClone(baseline);
  current.components.schemas.Widget.properties.note = { type: "string" };
  assert.deepEqual(findBreakingChanges(baseline, current), []);
});

test("accepts equivalent OpenAPI 3.1 type arrays", () => {
  const nullableBaseline = structuredClone(baseline);
  nullableBaseline.openapi = "3.1.0";
  nullableBaseline.components.schemas.Widget.properties.note = { type: ["string", "null"] };
  const current = structuredClone(nullableBaseline);
  current.components.schemas.Widget.properties.note.type.reverse();
  assert.deepEqual(findBreakingChanges(nullableBaseline, current), []);
});

test("rejects removed fields, enum changes and new required parameters", () => {
  const current = structuredClone(baseline);
  delete current.components.schemas.Widget.properties.id;
  current.components.schemas.Widget.properties.status.enum.push("ARCHIVED");
  current.paths["/widgets"].get.parameters.push({
    name: "tenant", in: "header", required: true, schema: { type: "string" }
  });
  const errors = findBreakingChanges(baseline, current);
  assert.ok(errors.some((error) => error.includes("properties.id: schema removed")));
  assert.ok(errors.some((error) => error.includes("enum values changed")));
  assert.ok(errors.some((error) => error.includes("new required parameter header:tenant")));
});

test("rejects changed cardinality and scalar constraints", () => {
  const constrainedBaseline = structuredClone(baseline);
  constrainedBaseline.components.schemas.Widget.properties.tags = {
    type: "array", items: { type: "string" }, maxItems: 500
  };
  const current = structuredClone(constrainedBaseline);
  current.components.schemas.Widget.properties.status.maxLength = 32;
  current.components.schemas.Widget.properties.tags.maxItems = 100;
  const errors = findBreakingChanges(constrainedBaseline, current);
  assert.ok(errors.some((error) => error.includes("maxLength changed")));
  assert.ok(errors.some((error) => error.includes("maxItems changed from 500 to 100")));
});

test("rejects generated artifact drift", () => {
  const generated = structuredClone(baseline);
  generated.info.title = "drifted";
  assert.ok(verifyContracts({ baseline, committed: baseline, generated })[0].includes("differs"));
});
