#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const HTTP_METHODS = new Set(["get", "put", "post", "delete", "options", "head", "patch", "trace"]);

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function stable(value) {
  if (Array.isArray(value)) {
    return value.map(stable);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  }
  return value;
}

function same(left, right) {
  return JSON.stringify(stable(left)) === JSON.stringify(stable(right));
}

function sameSchemaType(left, right) {
  const normalize = (value) => Array.isArray(value) ? [...value].sort() : value;
  return same(normalize(left), normalize(right));
}

function resolve(document, value) {
  if (!value?.$ref) {
    return value;
  }
  if (!value.$ref.startsWith("#/")) {
    return value;
  }
  return value.$ref.slice(2).split("/").reduce(
    (current, segment) => current?.[segment.replaceAll("~1", "/").replaceAll("~0", "~")],
    document
  );
}

function compareSchema(baselineDocument, currentDocument, baselineValue, currentValue, location, errors, seen) {
  if (!currentValue) {
    errors.push(`${location}: schema removed`);
    return;
  }

  const baselineRef = baselineValue?.$ref;
  const currentRef = currentValue?.$ref;
  if (baselineRef || currentRef) {
    if (baselineRef !== currentRef) {
      errors.push(`${location}: schema reference changed from ${baselineRef ?? "inline"} to ${currentRef ?? "inline"}`);
      return;
    }
    if (baselineRef) {
      const key = `${baselineRef}|${location}`;
      if (seen.has(key)) return;
      seen.add(key);
      compareSchema(
        baselineDocument,
        currentDocument,
        resolve(baselineDocument, baselineValue),
        resolve(currentDocument, currentValue),
        baselineRef,
        errors,
        seen
      );
      return;
    }
  }

  if (!sameSchemaType(baselineValue?.type, currentValue?.type)) {
    errors.push(`${location}: type changed from ${String(baselineValue?.type)} to ${String(currentValue?.type)}`);
  }

  for (const keyword of [
    "format", "nullable", "readOnly", "writeOnly",
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
    "minLength", "maxLength", "minItems", "maxItems", "uniqueItems"
  ]) {
    if (baselineValue?.[keyword] !== currentValue?.[keyword]) {
      errors.push(`${location}: ${keyword} changed from ${String(baselineValue?.[keyword])} to ${String(currentValue?.[keyword])}`);
    }
  }

  if (!same(baselineValue?.enum, currentValue?.enum)) {
    errors.push(`${location}: enum values changed`);
  }

  const baselineRequired = [...(baselineValue?.required ?? [])].sort();
  const currentRequired = [...(currentValue?.required ?? [])].sort();
  if (!same(baselineRequired, currentRequired)) {
    errors.push(`${location}: required properties changed`);
  }

  for (const [name, property] of Object.entries(baselineValue?.properties ?? {})) {
    compareSchema(
      baselineDocument,
      currentDocument,
      property,
      currentValue?.properties?.[name],
      `${location}.properties.${name}`,
      errors,
      seen
    );
  }

  if (baselineValue?.items) {
    compareSchema(
      baselineDocument,
      currentDocument,
      baselineValue.items,
      currentValue?.items,
      `${location}.items`,
      errors,
      seen
    );
  }

  for (const keyword of ["allOf", "oneOf", "anyOf"]) {
    const baselineVariants = baselineValue?.[keyword] ?? [];
    const currentVariants = currentValue?.[keyword] ?? [];
    if (baselineVariants.length !== currentVariants.length) {
      errors.push(`${location}: ${keyword} variants changed`);
      continue;
    }
    baselineVariants.forEach((variant, index) => compareSchema(
      baselineDocument,
      currentDocument,
      variant,
      currentVariants[index],
      `${location}.${keyword}[${index}]`,
      errors,
      seen
    ));
  }
}

function parametersFor(document, pathItem, operation) {
  return [...(pathItem?.parameters ?? []), ...(operation?.parameters ?? [])]
    .map((parameter) => resolve(document, parameter));
}

export function findBreakingChanges(baseline, current) {
  const errors = [];
  if (baseline.openapi !== current.openapi) {
    errors.push(`OpenAPI version changed from ${baseline.openapi} to ${current.openapi}`);
  }
  if (baseline.info?.version !== current.info?.version) {
    errors.push(`API contract version changed from ${baseline.info?.version} to ${current.info?.version}`);
  }

  for (const [path, baselinePathItem] of Object.entries(baseline.paths ?? {})) {
    const currentPathItem = current.paths?.[path];
    if (!currentPathItem) {
      errors.push(`${path}: path removed`);
      continue;
    }
    for (const [method, baselineOperation] of Object.entries(baselinePathItem)) {
      if (!HTTP_METHODS.has(method)) continue;
      const currentOperation = currentPathItem[method];
      const location = `${method.toUpperCase()} ${path}`;
      if (!currentOperation) {
        errors.push(`${location}: operation removed`);
        continue;
      }

      const baselineParameters = parametersFor(baseline, baselinePathItem, baselineOperation);
      const currentParameters = parametersFor(current, currentPathItem, currentOperation);
      const baselineKeys = new Set(baselineParameters.map((parameter) => `${parameter.in}:${parameter.name}`));
      for (const parameter of baselineParameters) {
        const key = `${parameter.in}:${parameter.name}`;
        const replacement = currentParameters.find((candidate) => `${candidate.in}:${candidate.name}` === key);
        if (!replacement) {
          errors.push(`${location}: parameter ${key} removed`);
          continue;
        }
        if (Boolean(parameter.required) !== Boolean(replacement.required)) {
          errors.push(`${location}: parameter ${key} required flag changed`);
        }
        compareSchema(baseline, current, parameter.schema, replacement.schema, `${location} parameter ${key}`, errors, new Set());
      }
      for (const parameter of currentParameters) {
        const key = `${parameter.in}:${parameter.name}`;
        if (parameter.required && !baselineKeys.has(key)) {
          errors.push(`${location}: new required parameter ${key}`);
        }
      }

      for (const [status, baselineResponseValue] of Object.entries(baselineOperation.responses ?? {})) {
        const currentResponseValue = currentOperation.responses?.[status];
        if (!currentResponseValue) {
          errors.push(`${location}: response ${status} removed`);
          continue;
        }
        const baselineResponse = resolve(baseline, baselineResponseValue);
        const currentResponse = resolve(current, currentResponseValue);
        for (const [contentType, baselineMedia] of Object.entries(baselineResponse?.content ?? {})) {
          const currentMedia = currentResponse?.content?.[contentType];
          if (!currentMedia) {
            errors.push(`${location}: response ${status} content type ${contentType} removed`);
            continue;
          }
          compareSchema(
            baseline,
            current,
            baselineMedia.schema,
            currentMedia.schema,
            `${location} response ${status} ${contentType}`,
            errors,
            new Set()
          );
        }
      }
    }
  }

  for (const [name, schema] of Object.entries(baseline.components?.schemas ?? {})) {
    compareSchema(
      baseline,
      current,
      schema,
      current.components?.schemas?.[name],
      `#/components/schemas/${name}`,
      errors,
      new Set()
    );
  }
  return [...new Set(errors)];
}

export function verifyContracts({ baseline, committed, generated }) {
  const errors = [];
  if (!same(committed, generated)) {
    errors.push("Generated OpenAPI differs from contracts/openapi/current.json; regenerate and commit the artifact");
  }
  errors.push(...findBreakingChanges(baseline, committed));
  return errors;
}

function parseArguments(arguments_) {
  const values = Object.fromEntries(arguments_.map((value, index) => [value, arguments_[index + 1]]));
  return {
    baselinePath: values["--baseline"],
    committedPath: values["--committed"],
    generatedPath: values["--generated"]
  };
}

function main() {
  const { baselinePath, committedPath, generatedPath } = parseArguments(process.argv.slice(2));
  if (!baselinePath || !committedPath || !generatedPath) {
    console.error("Usage: check-openapi-compatibility.mjs --baseline <file> --committed <file> --generated <file>");
    process.exitCode = 2;
    return;
  }
  const errors = verifyContracts({
    baseline: readJson(baselinePath),
    committed: readJson(committedPath),
    generated: readJson(generatedPath)
  });
  if (errors.length > 0) {
    console.error(`OpenAPI contract check failed (${errors.length}):`);
    errors.forEach((error) => console.error(`- ${error}`));
    process.exitCode = 1;
    return;
  }
  console.log("OpenAPI contract check passed: generated artifact is current and no breaking changes were found.");
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
