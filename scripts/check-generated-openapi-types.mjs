#!/usr/bin/env node

import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const frontendRoot = join(repositoryRoot, "frontend");
const committedRoot = join(frontendRoot, "src", "api", "generated");
const temporaryRoot = mkdtempSync(join(tmpdir(), "store-analytics-openapi-types-"));
const executable = join(frontendRoot, "node_modules", ".bin", process.platform === "win32" ? "openapi-ts.cmd" : "openapi-ts");

function files(root) {
  if (!existsSync(root)) return [];
  return readdirSync(root, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => relative(root, join(entry.parentPath, entry.name)))
    .sort();
}

try {
  const result = spawnSync(executable, [
    "-i", join(repositoryRoot, "contracts", "openapi", "current.json"),
    "-o", temporaryRoot,
    "-p", "@hey-api/typescript",
    "--silent"
  ], { cwd: frontendRoot, encoding: "utf8" });
  if (result.status !== 0) {
    process.stderr.write(result.stderr || result.stdout);
    process.exitCode = result.status ?? 1;
  } else {
    const expectedFiles = files(committedRoot);
    const generatedFiles = files(temporaryRoot);
    const drift = [];
    if (JSON.stringify(expectedFiles) !== JSON.stringify(generatedFiles)) {
      drift.push("generated file list changed");
    }
    for (const file of new Set([...expectedFiles, ...generatedFiles])) {
      const committed = existsSync(join(committedRoot, file)) ? readFileSync(join(committedRoot, file), "utf8") : undefined;
      const generated = existsSync(join(temporaryRoot, file)) ? readFileSync(join(temporaryRoot, file), "utf8") : undefined;
      if (committed !== generated) drift.push(file);
    }
    if (drift.length > 0) {
      console.error(`Generated transport types are stale: ${drift.join(", ")}. Run npm run contracts:generate.`);
      process.exitCode = 1;
    } else {
      console.log("Generated transport types match the committed OpenAPI contract.");
    }
  }
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
