import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

const MINIMUM_LENGTH = 12;
const MAXIMUM_LENGTH = 128;
const BCRYPT_MAXIMUM_BYTES = 72;

const [inputPath, outputPath] = process.argv.slice(2);
if (!inputPath || !outputPath) {
  throw new Error("Usage: node generate-password-blocklist.mjs <input> <output>");
}

const blockedDigests = new Set();
const sourcePasswords = readFileSync(inputPath, "utf8").split(/\r?\n/u);
for (const sourcePassword of sourcePasswords) {
  const canonicalPassword = sourcePassword.normalize("NFC").toLowerCase();
  const codePointLength = Array.from(canonicalPassword).length;
  if (
    codePointLength < MINIMUM_LENGTH
    || codePointLength > MAXIMUM_LENGTH
    || Buffer.byteLength(canonicalPassword, "utf8") > BCRYPT_MAXIMUM_BYTES
    || /\p{Cc}/u.test(canonicalPassword)
  ) {
    continue;
  }
  blockedDigests.add(
    createHash("sha256").update(canonicalPassword, "utf8").digest("hex"),
  );
}

const sortedDigests = [...blockedDigests].sort();
writeFileSync(outputPath, `${sortedDigests.join("\n")}\n`, {
  encoding: "ascii",
  flag: "wx",
});

process.stdout.write(`${JSON.stringify({
  sourceEntries: sourcePasswords.length - 1,
  blockedEntries: sortedDigests.length,
  outputSha256: createHash("sha256")
    .update(readFileSync(outputPath))
    .digest("hex"),
})}\n`);
