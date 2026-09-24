#!/usr/bin/env bun
/** Pin and validate the Claude Code version recorded by the Docker e2e receipt.
 *
 *  V4-145: converted to TypeScript (bun), faithfully — including the failure path, which throws and
 *  exits non-zero exactly as the Python does. The stderr TEXT therefore differs in FORM (a JS stack
 *  rather than a Python traceback); that is not faked, and the contract that matters is unchanged:
 *  exit 0 with the PASS line on stdout, non-zero on every failure. The only caller
 *  (tools/e2e/docker/run.sh:103) reads the exit code and nothing else.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "../../..");
const VERSIONS = resolve(ROOT, "core/src/main/kotlin/splice/core/Versions.kt");
const DOCKERFILE = resolve(ROOT, "tools/e2e/docker/Dockerfile");
const LIB = resolve(ROOT, "tools/e2e/docker/lib.sh");
const LIB_TS = resolve(ROOT, "tools/e2e/docker/lib.ts");
// The container scenarios: each sources lib.sh (where the receipt contract lives) and runs its
// version step, so neither can write a receipt that skips the pin.
const SCENARIOS = ["inside.sh", "upgrade.sh"].map((name) => resolve(ROOT, "tools/e2e/docker", name));
const RUN = resolve(ROOT, "tools/e2e/docker/run.sh");

function rel(p: string): string {
  return p.startsWith(ROOT + "/") ? p.slice(ROOT.length + 1) : p;
}

function match(path: string, pattern: RegExp, label: string): string {
  const found = pattern.exec(readFileSync(path, "utf8"));
  if (found === null) {
    throw new Error(`${label} is missing or malformed in ${rel(path)}`);
  }
  return found[1];
}

function sourcePin(): string {
  const tested = match(
    VERSIONS,
    /public const val TESTED_CLAUDE_CODE: String = "([0-9]+(?:\.[0-9]+)+)"/,
    "TESTED_CLAUDE_CODE",
  );
  const image = match(DOCKERFILE, /ARG CLAUDE_CODE_VERSION=([0-9]+(?:\.[0-9]+)+)/, "Dockerfile pin");
  if (image !== tested) {
    throw new Error(`Dockerfile pins Claude Code ${image}, but Versions.kt records ${tested}`);
  }

  const lib = readFileSync(LIB, "utf8");
  const requiredLib = [
    'CLAUDE_CODE_ACTUAL="$(claude --version',
    '[ "$CLAUDE_CODE_ACTUAL" = "$TESTED_CLAUDE_CODE" ]',
    'finish "$STEPS_FILE" "$RECEIPT" "$FAILED" "$CLAUDE_CODE_ACTUAL" "$TESTED_CLAUDE_CODE"',
  ];
  for (const token of requiredLib) {
    if (!lib.includes(token)) {
      throw new Error(`lib.sh does not enforce receipt contract token: ${token}`);
    }
  }
  // lib.sh hands the two versions to lib.ts's receipt writer in that order; the writer must put them
  // in the receipt under the names validateReceipt reads.
  const writer = readFileSync(LIB_TS, "utf8");
  const requiredWriter = [
    'claudeCodeVersion: arg(argv, 3, "actual")',
    'testedClaudeCodeVersion: arg(argv, 4, "tested")',
  ];
  for (const token of requiredWriter) {
    if (!writer.includes(token)) {
      throw new Error(`lib.ts does not write receipt contract field: ${token}`);
    }
  }
  const requiredScenario = [
    'source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"',
    'step "Claude Code version matches the splice tested pin" client_version_receipt',
  ];
  for (const scenario of SCENARIOS) {
    const text = readFileSync(scenario, "utf8");
    for (const token of requiredScenario) {
      if (!text.includes(token)) {
        throw new Error(`${rel(scenario)} does not carry the receipt contract: ${token}`);
      }
    }
  }

  const run = readFileSync(RUN, "utf8");
  const requiredRun = [
    '--build-arg "CLAUDE_CODE_VERSION=$TESTED_CLAUDE_CODE"',
    '-e "SPLICE_TESTED_CLAUDE_CODE=$TESTED_CLAUDE_CODE"',
  ];
  for (const token of requiredRun) {
    if (!run.includes(token)) {
      throw new Error(`run.sh does not carry the tested pin: ${token}`);
    }
  }
  return tested;
}

type Receipt = Record<string, unknown>;

function validateReceipt(path: string, tested: string): void {
  const receipt = JSON.parse(readFileSync(path, "utf8")) as Receipt;
  const actual = receipt["claudeCodeVersion"];
  const receiptTested = receipt["testedClaudeCodeVersion"];
  if (!actual) {
    throw new Error("receipt has no actual claudeCodeVersion");
  }
  if (actual !== tested || receiptTested !== tested) {
    throw new Error(
      `receipt used Claude Code ${pyRepr(actual)} against ${pyRepr(receiptTested)}; ` +
        `Versions.kt records ${pyRepr(tested)}`,
    );
  }
  const steps = (receipt["steps"] as Receipt[] | undefined) ?? [];
  const versionSteps = steps.filter(
    (step) => step["step"] === "Claude Code version matches the splice tested pin",
  );
  if (versionSteps.length !== 1 || versionSteps[0]["verdict"] !== "PASS") {
    throw new Error("receipt lacks one passing Claude Code version step");
  }
}

/** Python repr(), for the failure message the original builds with !r. */
function pyRepr(v: unknown): string {
  if (v === null || v === undefined) return "None";
  if (typeof v === "string") return `'${v.replaceAll("\\", "\\\\").replaceAll("'", "\\'")}'`;
  return String(v);
}

function main(): void {
  const tested = sourcePin();
  const argv = process.argv.slice(2);
  if (argv.length > 1) {
    process.stderr.write("usage: receipt-selftest.py [receipt.json]\n");
    process.exit(1);
  }
  if (argv.length === 1) {
    validateReceipt(argv[0], tested);
  }
  process.stdout.write(`receipt selftest: PASS (Claude Code ${tested})\n`);
}

if (import.meta.main) {
  main();
}
