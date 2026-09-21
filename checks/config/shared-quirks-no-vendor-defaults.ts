#!/usr/bin/env bun
/**
 * DR-1xx — a SHARED *Quirks type may not carry a vendor-identity default.
 *
 * WHY THIS EXISTS. Every dialect that shares the responses wire also shares one `*Quirks` data
 * class, and a default on that class is a default for EVERY dialect that uses it. A vendor fact —
 * a model-id regex, a vendor header name, a vendor host, a measured lite wire byte — parked in the
 * shared class as a default is therefore not a convenience: it is one dialect's fact silently
 * applied to all of them, and the next dialect added inherits it without anyone deciding anything.
 * The class is where the SHAPE of the wire is declared; which vendor's shape it is belongs at the
 * call site, which is what this wall forces by refusing the default.
 *
 * THE FOUR SHAPES THAT FAIL, and each is a different way the same fact leaks in:
 *   · a `Regex` default (or a field NAMED *Models / *ModelRegex) — a model-id fact;
 *   · a field whose name contains `Header` with a default, or any default shaped like a vendor
 *     header name (`"x-..."`) — a header-name fact;
 *   · a default containing a vendor HOST — a routing fact;
 *   · a lite-gated default carrying a measured wire byte — `liteTextVerbosity = "low"`,
 *     `sendClientMetadata = true`, `liteParallelToolCalls = true`. `false` and `null` are OMIT
 *     (the vendor's absence), not a measured byte, so they stay green.
 *
 * WHAT IS GREEN: `null` (an explicit "no vendor fact here"), a plain absence of a default, and any
 * default that is not shaped like one of the four above — `minImageEdgePx: Int? = null` and
 * `extra: String? = null` in the compliant fixture are exactly that, and they are the boring cases
 * a checker like this gets wrong by over-matching.
 *
 * SCOPE IS DERIVED, not listed: every .kt under `gateway/dialect-{name}/src/main` is read from the
 * source tree. A provider module (`gateway/provider-codex`, ...) is NOT in scope — its own
 * `CodexQuirks` may hold vendor facts, because it IS the vendor — and the selftest pins that
 * asymmetry with a temp tree holding both, so a future widening of the glob cannot silently pull
 * provider modules in.
 *
 * Usage:
 *     bun checks/config/shared-quirks-no-vendor-defaults.ts check <root>
 *     bun checks/config/shared-quirks-no-vendor-defaults.ts report <root>
 *     bun checks/config/shared-quirks-no-vendor-defaults.ts --selftest
 *
 * A BARE RUN IS `check` ON THE REPO ROOT — the one mode this file has, because `check` is what the
 * gate runs and there is no separate census default to mis-invoke. `report` is the census and it
 * sits behind an explicit verb the gate never uses.
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

// parents[2]: this file lives at checks/config/, so the repo root is two levels up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

const CLASS_HEAD = /public data class (\w+Quirks)\s*\(/g;
const HEADER_DEFAULT = /^["']x-[A-Za-z0-9-]+["']$/i;
const HOST_DEFAULT = /https?:\/\/|api\.openai\.|api\.x\.ai|anthropic\.com|openrouter\.ai|moonshot\.cn/i;

/** Every dialect module's src/main: the §2.2 home dialects/<name>/ and, until restructure PR 3 has moved
 *  the last one, the old gateway/dialect-<name>/ — a checker that only walked one of the two would read a
 *  moved dialect as "no shared *Quirks class" and red the wall for a directory it stopped looking in. */
function dialectQuirkFiles(root: string): string[] {
  const files: string[] = [];
  const homes: string[] = [];
  const gw = join(root, "gateway");
  if (existsSync(gw)) {
    for (const n of readdirSync(gw).sort()) if (n.startsWith("dialect-") && statSync(join(gw, n)).isDirectory()) homes.push(join(gw, n));
  }
  const grouped = join(root, "dialects");
  if (existsSync(grouped)) {
    for (const n of readdirSync(grouped).sort()) if (statSync(join(grouped, n)).isDirectory()) homes.push(join(grouped, n));
  }
  for (const home of homes) {
    const main = join(home, "src", "main");
    if (!existsSync(main) || !statSync(main).isDirectory()) continue;
    const found = [...new Bun.Glob("**/*.kt").scanSync({ cwd: main, followSymlinks: true })].sort();
    for (const rel of found) files.push(join(main, rel));
  }
  return files;
}

/** Return the primary-constructor text inside the parens at [start], or null. */
function extractConstructor(source: string, start: number): string | null {
  let i = start;
  let depth = 0;
  let inString = false;
  let quote = "";
  let escape = false;
  let bodyStart: number | null = null;
  while (i < source.length) {
    const ch = source[i];
    if (inString) {
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === quote) inString = false;
      i += 1;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      i += 1;
      continue;
    }
    if (ch === "/" && i + 1 < source.length && source[i + 1] === "/") {
      const nl = source.indexOf("\n", i);
      i = nl < 0 ? source.length : nl;
      continue;
    }
    if (ch === "/" && i + 1 < source.length && source[i + 1] === "*") {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? source.length : end + 2;
      continue;
    }
    if (ch === "(") {
      depth += 1;
      if (depth === 1) bodyStart = i + 1;
      i += 1;
      continue;
    }
    if (ch === ")") {
      depth -= 1;
      if (depth === 0 && bodyStart !== null) return source.slice(bodyStart, i);
      i += 1;
      continue;
    }
    i += 1;
  }
  return null;
}

function splitParams(body: string): string[] {
  const parts: string[] = [];
  let buf: string[] = [];
  let depth = 0;
  let inString = false;
  let quote = "";
  let escape = false;
  let i = 0;
  while (i < body.length) {
    const ch = body[i];
    if (inString) {
      buf.push(ch);
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === quote) inString = false;
      i += 1;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (ch === "/" && i + 1 < body.length && body[i + 1] === "/") {
      const nl = body.indexOf("\n", i);
      i = nl < 0 ? body.length : nl;
      continue;
    }
    if (ch === "/" && i + 1 < body.length && body[i + 1] === "*") {
      const end = body.indexOf("*/", i + 2);
      i = end < 0 ? body.length : end + 2;
      continue;
    }
    if ("({[<".includes(ch)) {
      depth += 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (")}]>".includes(ch)) {
      depth -= 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (ch === "," && depth === 0) {
      parts.push(buf.join(""));
      buf = [];
      i += 1;
      continue;
    }
    buf.push(ch);
    i += 1;
  }
  if (buf.length > 0) parts.push(buf.join(""));
  return parts;
}

const PARAM = /\bval\s+(\w+)\s*:\s*([^=]+?)(?:\s*=\s*(.*))?\s*$/s;

type Field = [string, string, string | null];

function parseParams(body: string): Field[] {
  const fields: Field[] = [];
  for (const raw of splitParams(body)) {
    const text = raw.trim();
    if (!text) continue;
    const match = PARAM.exec(text);
    if (match === null) continue;
    const defaultText = match[3] === undefined ? null : match[3].trim();
    fields.push([match[1], match[2].trim(), defaultText]);
  }
  return fields;
}

function parseSharedQuirks(source: string): [string, Field[]][] {
  const found: [string, Field[]][] = [];
  for (const m of source.matchAll(CLASS_HEAD)) {
    const body = extractConstructor(source, (m.index as number) + m[0].length - 1);
    if (body === null) continue;
    found.push([m[1], parseParams(body)]);
  }
  return found;
}

const isNullDefault = (d: string | null): boolean => d !== null && d.trim() === "null";

/** Return the shape label, or null if this field is not a vendor identity fact. */
function vendorShaped(name: string, typeText: string, def: string | null): string | null {
  const typeFlat = typeText.replace(/\s+/g, "");
  if (typeFlat.includes("Regex") || name.endsWith("Models") || name.includes("ModelRegex")) {
    return "model-id";
  }
  if (name.includes("Header")) return "header-name";
  if (def === null) return null;
  const stripped = def.trim();
  const inner = stripped.replace(/^["']|["']$/g, "");
  if (inner.toLowerCase().startsWith("x-") && HEADER_DEFAULT.test(`"${inner}"`)) return "header-name";
  if (HOST_DEFAULT.test(stripped)) return "vendor-host";
  return null;
}

/** A lite-gated default that encodes a measured vendor wire byte.
 *
 *  Name contains lite (responsesLite*, liteTextVerbosity, emitEmptyLiteInstructions,
 *  liteParallelToolCalls) or is sendClientMetadata, which is lite-gated without the
 *  prefix. false and null are omit, not a measured byte. */
function liteGatedWireByte(name: string, def: string | null): string | null {
  if (def === null || isNullDefault(def)) return null;
  if (def.trim() === "false") return null;
  if (!name.toLowerCase().includes("lite") && name !== "sendClientMetadata") return null;
  return "lite-wire-byte";
}

/** Return (kind, detail) where kind is required, clean-null, clean-unshaped, or fail. */
function classify(name: string, typeText: string, def: string | null): [string, string] {
  const identity = vendorShaped(name, typeText, def);
  const lite = liteGatedWireByte(name, def);
  const shape = identity ?? lite;
  if (def === null) return ["required", "no default"];
  if (isNullDefault(def)) return ["clean-null", "null default" + (shape ? ` (${shape})` : "")];
  if (shape === null) {
    return [
      "clean-unshaped",
      "default is not a model id, header name, vendor host, or lite-gated measured wire byte",
    ];
  }
  return ["fail", `${shape} default: ${def}`];
}

function checkSource(label: string, source: string): string[] {
  const problems: string[] = [];
  const classes = parseSharedQuirks(source);
  if (classes.length === 0) return problems;
  for (const [className, fields] of classes) {
    if (fields.length === 0) {
      problems.push(`${label}: ${className} has no parsed fields — refusing to pass vacuously`);
      continue;
    }
    for (const [name, typeText, def] of fields) {
      const [kind, detail] = classify(name, typeText, def);
      if (kind === "fail") problems.push(`${label}: ${className}.${name} ${detail}`);
    }
  }
  return problems;
}

function checkTree(root: string): string[] {
  const problems: string[] = [];
  let saw = false;
  for (const path of dialectQuirkFiles(root)) {
    const source = readFileSync(path, "utf8");
    const classes = parseSharedQuirks(source);
    if (classes.length === 0) continue;
    saw = true;
    problems.push(...checkSource(relative(root, path).split("\\").join("/"), source));
  }
  if (!saw) problems.push("no shared *Quirks data class under dialects/*/src/main (or gateway/dialect-*/src/main)");
  return problems;
}

const COMPLIANT = `
public data class ResponsesQuirks(
    val providerTag: String,
    val summaryRejectModelRegex: Regex? = null,
    val effortMaxRejectModelRegex: Regex? = null,
    val responsesLiteHeader: String? = null,
    val liteTextVerbosity: String? = null,
    val sendClientMetadata: Boolean = false,
    val liteParallelToolCalls: Boolean = false,
    val minImageEdgePx: Int? = null,
    val extra: String? = null,
)
`;

const REGEX_VIOLATION = `
public data class ResponsesQuirks(
    val providerTag: String,
    val effortMaxRejectModelRegex: Regex? = Regex("mini", RegexOption.IGNORE_CASE),
)
`;

const HEADER_VIOLATION = `
public data class ResponsesQuirks(
    val providerTag: String,
    val responsesLiteHeader: String? = "x-openai-internal-codex-responses-lite",
)
`;

const HOST_VIOLATION = `
public data class ResponsesQuirks(
    val providerTag: String,
    val baseUrl: String? = "https://api.openai.com/v1",
)
`;

const LITE_VERBOSITY_VIOLATION = `
public data class ResponsesQuirks(
    val providerTag: String,
    val liteTextVerbosity: String? = "low",
)
`;

const METADATA_VIOLATION = `
public data class ResponsesQuirks(
    val providerTag: String,
    val sendClientMetadata: Boolean = true,
)
`;

const PARALLEL_ON_VIOLATION = `
public data class ResponsesQuirks(
    val providerTag: String,
    val liteParallelToolCalls: Boolean = true,
)
`;

const VENDOR_FILE = `
public data class CodexQuirks(
    val effortMaxRejectModelRegex: Regex? = Regex("mini", RegexOption.IGNORE_CASE),
)
`;

function selftest(): number {
  const failures: string[] = [];
  const has = (hits: string[], needle: string): boolean => hits.some((h) => h.includes(needle));

  if (checkSource("compliant", COMPLIANT).length > 0) {
    failures.push("compliant null vendor knobs plus a boring null extra must be GREEN");
  }
  if (!has(checkSource("regex", REGEX_VIOLATION), "effortMaxRejectModelRegex")) {
    failures.push("synthetic Regex default must be RED by field name");
  }
  if (!has(checkSource("header", HEADER_VIOLATION), "responsesLiteHeader")) {
    failures.push("synthetic header-name default must be RED by field name");
  }
  if (!has(checkSource("host", HOST_VIOLATION), "baseUrl")) {
    failures.push("synthetic vendor-host default must be RED by field name");
  }
  if (!has(checkSource("verbosity", LITE_VERBOSITY_VIOLATION), "liteTextVerbosity")) {
    failures.push("synthetic liteTextVerbosity default must be RED by field name");
  }
  if (!has(checkSource("metadata", METADATA_VIOLATION), "sendClientMetadata")) {
    failures.push("synthetic sendClientMetadata true default must be RED by field name");
  }
  if (!has(checkSource("parallel-on", PARALLEL_ON_VIOLATION), "liteParallelToolCalls")) {
    failures.push("synthetic liteParallelToolCalls true default must be RED by field name");
  }

  const tmp = mkdtemp();
  try {
    const dialect = join(tmp, "dialects/openai-responses/src/main/kotlin");
    const vendor = join(tmp, "providers/codex/src/main/kotlin");
    mkdirSync(dialect, { recursive: true });
    mkdirSync(vendor, { recursive: true });
    writeFileSync(join(dialect, "ResponsesQuirks.kt"), COMPLIANT, "utf8");
    writeFileSync(join(vendor, "CodexQuirks.kt"), VENDOR_FILE, "utf8");
    const live = checkTree(tmp);
    if (live.length > 0) {
      failures.push("temp tree with compliant dialect and vendor Regex must be GREEN, got: " + live.join("; "));
    }
    writeFileSync(join(dialect, "ResponsesQuirks.kt"), REGEX_VIOLATION, "utf8");
    if (!has(checkTree(tmp), "effortMaxRejectModelRegex")) {
      failures.push("temp tree Regex default must be RED");
    }
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }

  if (failures.length > 0) {
    process.stdout.write("shared-quirks-no-vendor-defaults SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "shared-quirks-no-vendor-defaults SELFTEST OK — null vendor knobs, false/omit " +
      "lite booleans, and a boring null extra are green; Regex, header-name, " +
      "vendor-host, and lite-gated measured-wire-byte defaults are red by name; a " +
      "provider-codex CodexQuirks Regex is out of scope\n",
  );
  return 0;
}

function mkdtemp(): string {
  const dir = join(tmpdir(), `shared-quirks-${process.pid}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(dir, { recursive: true });
  return dir;
}

function report(root: string): void {
  process.stdout.write("shared-quirks-no-vendor-defaults fields:\n");
  for (const path of dialectQuirkFiles(root)) {
    const source = readFileSync(path, "utf8");
    for (const [className, fields] of parseSharedQuirks(source)) {
      process.stdout.write(`  ${relative(root, path).split("\\").join("/")} ${className}\n`);
      for (const [name, typeText, def] of fields) {
        const [kind, detail] = classify(name, typeText, def);
        process.stdout.write(`    ${kind}: ${name}: ${detail}\n`);
      }
    }
  }
}

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  let root = ROOT;
  for (const arg of argv) {
    if (arg !== "check" && arg !== "report" && !arg.startsWith("-")) {
      root = arg;
      break;
    }
  }
  if (!existsSync(root)) {
    process.stderr.write("shared-quirks-no-vendor-defaults: tree missing\n");
    return 1;
  }
  if (argv.includes("report")) {
    report(root);
    return 0;
  }
  const problems = checkTree(root);
  if (problems.length > 0) {
    process.stdout.write("shared-quirks-no-vendor-defaults RED:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    return 1;
  }
  process.stdout.write(
    "shared-quirks-no-vendor-defaults GREEN: no vendor-fact default on a shared " +
      "*Quirks type under dialects/*/src/main\n",
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
