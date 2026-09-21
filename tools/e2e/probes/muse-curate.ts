#!/usr/bin/env bun
/** Redact and curate a Muse live capture into tools/e2e/receipts/muse-YYYYMMDD/.
 *
 *  The live head-through-splice turn needs Marcos's login and is recorded later.
 *  This script only curates an already-redacted-or-raw capture: secrets never survive
 *  in the output. Self-test: muse-curate.ts --selftest
 *
 *  V4-145: converted to TypeScript (bun). THIS FILE REWRITES A FILE, so the written BYTES are the
 *  artifact and they are what the port had to match, not a stdout comparison. The Python serializer
 *  is not JSON.stringify, and the artifact differential found FOUR places it differs, three of which
 *  I had not predicted: ensure_ascii escaping, float rendering, integers past 2^53, and — the one
 *  that needs a parser rather than a dumper — KEY ORDER, because JS reorders integer-like keys
 *  ascending while Python keeps insertion order. Objects are therefore carried as ordered pair lists
 *  rather than plain JS objects, and numbers keep their raw token so int and float stay distinct.
 */
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const SECRET_KEYS = new Set([
  "access_token",
  "api_key",
  "authorization",
  "cookie",
  "set-cookie",
  "user_email",
  "user_id",
  "user_full_name",
  "email",
  "refresh_token",
  "x-api-key",
]);
const SECRET_KEY_RE = /(token|secret|password|authorization|api[_-]?key|cookie|email)/i;
const REDACTED = "REDACTED";

// The Python-compatible JSON layer lives in tools/e2e/src/compat/python-json.ts: ONE source for the four ways
// json.dumps differs from JSON.stringify, shared with the other checks that re-emit JSON rather
// than a copy per script. Carried out of this file after the artifact differential found them.
import { loads, dumpsIndent, obj, num, objGet, isPyNum, isPyObj, type PyValue, type PyObj, type PyNum } from "../src/compat/python-json.ts";
export { obj, num, objGet, isPyNum, isPyObj };
export type { PyValue, PyObj, PyNum };

// ── the curator ──────────────────────────────────────────────────────────────────────────────────

export function redact(value: PyValue, key: string | null = null): PyValue {
  if (key !== null && (SECRET_KEYS.has(key.toLowerCase()) || SECRET_KEY_RE.test(key))) {
    if (typeof value === "string" && value === "") {
      return value;
    }
    return REDACTED;
  }
  if (Array.isArray(value)) {
    return value.map((item) => redact(item));
  }
  if (isPyObj(value)) {
    return obj(value.__pyObj.map(([k, v]) => [k, redact(v, k)] as [string, PyValue]));
  }
  return value;
}

export function loadPayload(path: string, suffix: string): PyValue {
  const text = readFileSync(path, "utf8");
  if (suffix === ".jsonl") {
    return text
      .split("\n")
      .filter((line) => line.trim() !== "")
      .map((line) => loads(line));
  }
  return loads(text);
}

export function curate(src: string, destDir: string): string {
  mkdirSync(destDir, { recursive: true });
  const out = resolve(destDir, "mint-and-usage.json");
  const suffix = src.slice(src.lastIndexOf("."));
  writeFileSync(out, dumpsIndent(redact(loadPayload(src, suffix))) + "\n", "utf8");
  return out;
}

export function selftest(): void {
  const cleaned = redact(
    loads(`{
    "api_key": "real-secret-key",
    "access_token": "real-account-token",
    "user_email": "operator@example.test",
    "subs_usage": {"window": {"used_percent": 1, "window_duration_mins": 300}},
    "headers": {"Authorization": "Bearer real-secret-key"}
  }`),
  );
  const root = cleaned as PyObj;
  if (objGet(root, "api_key") !== REDACTED) throw new Error("api_key not redacted");
  if (objGet(root, "access_token") !== REDACTED) throw new Error("access_token not redacted");
  if (objGet(root, "user_email") !== REDACTED) throw new Error("user_email not redacted");
  const headers = objGet(root, "headers") as PyObj;
  if (objGet(headers, "Authorization") !== REDACTED) throw new Error("Authorization not redacted");
  const usage = objGet(root, "subs_usage") as PyObj;
  const window = objGet(usage, "window") as PyObj;
  const pct = objGet(window, "used_percent");
  if (!isPyNum(pct) || pct.__pyNum !== "1") {
    throw new Error("used_percent must survive redaction unchanged");
  }
  process.stdout.write("muse-curate selftest: PASS\n");
}

function main(argv: string[]): number {
  if (argv.length === 1 && argv[0] === "--selftest") {
    selftest();
    return 0;
  }
  if (argv.length !== 2) {
    process.stderr.write("usage: muse-curate.ts <capture.json|jsonl> <dest-dir>\n");
    process.stderr.write("       muse-curate.ts --selftest\n");
    return 2;
  }
  const written = curate(argv[0], argv[1]);
  process.stdout.write(`wrote ${written}\n`);
  return 0;
}

if (import.meta.main) {
  process.exit(main(process.argv.slice(2)));
}
