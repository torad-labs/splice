#!/usr/bin/env bun
/**
 * V4-44 — every operator-facing quirk KEY has a disposition in the documentation.
 *
 * WHY THIS EXISTS. QuirksConfig.kt is the finite quirk surface: the keys an operator may
 * write under [providers.X.quirks]. Its documentation lives in OTHER files — the example
 * config an operator copies, and the `splice add PROFILE` emitter — and nothing paired
 * the two. A key added to the type but documented nowhere, or a key retired while the doc
 * still describes it, drifts in silence. This is the shape that let six of DeepSeek's nine
 * accepted block types stay dropped for a whole campaign: a hand-authored list checked
 * against another hand-authored list, agreeing with each other and disagreeing with
 * reality. Sweeping the keys once closes the instance; this closes the class.
 *
 * DENOMINATOR, from the SOURCE, never a hand list. The primary constructor of every data
 * class in QuirksConfig.kt is parsed on disk, and each parameter yields its TOML key
 * (@SerialName when present, else the property name). 28 keys today across two classes,
 * 7 of them in the nested tool_surface table. A key added tomorrow is in scope with no
 * edit to this file. Two guards refuse a vacuous pass: the parsed @SerialName count must
 * equal the count in the comment-stripped file (parser drift), and a parse yielding zero
 * keys is a failure rather than a pass.
 *
 * WHY ITS OWN PARSER rather than a shared one: the sibling wall's class pattern is
 * `data class (Quirks-suffixed)`, which matches neither QuirksConfig nor ToolSurfaceConfig, and
 * widening it means editing a wall outside this row's fence. The two functions below are lifted
 * in behaviour — comment- and string-aware construction, so the KDoc interleaved between
 * QuirksConfig's parameters cannot swallow a parameter.
 *
 * DISPOSITION. Every parsed key must be accounted for, in one of three forms:
 *   documented — the key appears as a TOML key token (`key = ...`) in a surface file,
 *                line-leading or inside an inline table, commented or live. Commented is
 *                the normal spelling here: these docs show a key and its default without
 *                changing the daily-driven config;
 *   tabled     — the key's own nested table is declared (`[....tool_surface]`), which is
 *                how TOML documents a sub-table and how the example explains it;
 *   retired    — a machine-readable marker, `# retired: <key> — <reason>`, and the reason
 *                must be non-empty: a retirement with no written reason is an absence
 *                wearing a label and fails BY NAME like any other absence.
 * Absence is not a disposition. An undocumented key fails by name, naming its class and
 * property, so the fix is obvious without reading the type.
 *
 * WHAT IS NOT A DISPOSITION SURFACE, and why it matters. README.md is not a quirk
 * reference (409 lines; across all 28 keys it mentions store and code_mode, incidentally).
 * And gateway/app/src/main/kotlin/splice/app/cli/DoctorReportShape.kt names 16 quirk keys
 * in a RUNTIME doctor map — it is a report, not documentation, and counting it would make
 * this wall green with no documentation work at all. The surfaces are exactly the two
 * files an operator reads to write a quirk.
 *
 * NOT CAUGHT, and why.
 *   A key documented in the WRONG place. The check proves the key token exists in a
 *   surface, not that it sits under its own table. `enabled` is a generic name, so an
 *   unrelated `enabled =` line anywhere would satisfy it. What would catch it: a
 *   section-aware scan binding each key to its owning table path (quirks,
 *   quirks.tool_surface). Not built here because the inline-table spelling
 *   (`quirks = { mfjs = true, ... }`) makes the section walk more intricate than the
 *   drift it would catch.
 *   A key documented only in a THIRD file — a new profile emitter, or a docs/ page. The
 *   surface list is two paths, written below. A new emitter is invisible here until its
 *   path is added, so adding an emitter is a change to this checker too.
 *
 * SELFTEST. --selftest builds a temp tree and proves BOTH directions: GREEN on the
 * compliant form (key token, inline key, table header, retired marker with a reason);
 * RED naming a synthetic key appended to a temp copy of the source (the mutation this row
 * requires); RED on a retirement carrying no reason; RED on a key named only in a runtime
 * map; RED refusing to pass when the parse yields no keys; RED when the parsed @SerialName
 * count disagrees with the file.
 *
 * Usage:
 *     bun checks/config/quirks-keys-documented.ts check <root>
 *     bun checks/config/quirks-keys-documented.ts report <root>
 *     bun checks/config/quirks-keys-documented.ts --selftest
 *
 * A BARE RUN IS `check` ON THE REPO ROOT — the one gating mode, so there is no non-gating
 * default to mis-invoke. `report` is the census and sits behind an explicit verb the gate
 * never uses.
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// parents[2]: this file lives at checks/config/, so the repo root is two levels up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

// The denominator. Fixed path on purpose: a checker that silently loses its source is a
// checker that passes.
const SOURCE_REL = "gateway/core/src/main/kotlin/splice/core/topology/QuirksConfig.kt";

// The two files an operator reads to write a quirk: the copyable example config and the
// `splice add PROFILE` emitter. See NOT CAUGHT for what a third surface would mean.
const SURFACES = [
  "config/splice.example.toml",
  "gateway/app/src/main/kotlin/splice/app/cli/AddProfileCatalog.kt",
];

const DATA_CLASS = /(?:public\s+)?data class\s+(\w+)\s*\(/g;
const SERIAL_NAME = /@SerialName\(\s*"([^"]+)"\s*\)/g;
const PARAM = /\bval\s+(\w+)\s*:\s*([^=]+?)(?:\s*=\s*(.*))?\s*$/s;

/** One parsed constructor parameter: its TOML key, its table, its property. */
interface QuirkKey {
  klass: string;
  table: string;
  key: string;
  prop: string;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Remove line and block comments, respecting string literals.
 *
 *  Only the @SerialName count guard reads this. A raw count that included a commented
 *  annotation would red the wall for a doc change, which is the wrong signal. */
function stripComments(source: string): string {
  const out: string[] = [];
  let i = 0;
  let inString = false;
  let quote = "";
  let escape = false;
  while (i < source.length) {
    const ch = source[i];
    if (inString) {
      out.push(ch);
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === quote) inString = false;
      i += 1;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      out.push(ch);
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
    out.push(ch);
    i += 1;
  }
  return out.join("");
}

/** Return the primary-constructor text inside the parens at [start], or null.
 *
 *  [start] is the index of the opening paren. Comment- and string-aware: QuirksConfig
 *  interleaves a KDoc block between parameters, and a naive paren walk would either
 *  stop early or run past the constructor. */
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

/** Split a constructor body on top-level commas, dropping comments. */
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

/** The TOML table a class's keys are written under: QuirksConfig -> quirks,
 *  ToolSurfaceConfig -> quirks.tool_surface. Derived, never a hand list, so a third
 *  data class in the file is placed without editing this function. */
function tableFor(klass: string): string {
  let snake = klass.replace(/(?<!^)(?=[A-Z])/g, "_").toLowerCase();
  snake = snake.replace(/_config$/, "");
  return snake !== "quirks" ? `quirks.${snake}` : "quirks";
}

/** Return (keys, problems). problems is non-empty only on a parse that cannot be
 *  trusted, never on a merely undocumented key. */
function parseSource(source: string, label: string): { keys: QuirkKey[]; problems: string[] } {
  const problems: string[] = [];
  const keys: QuirkKey[] = [];
  let classes = 0;
  let serialsParsed = 0;
  for (const m of source.matchAll(DATA_CLASS)) {
    const body = extractConstructor(source, (m.index as number) + m[0].length - 1);
    if (body === null) {
      problems.push(`${label}: ${m[1]} constructor could not be parsed`);
      continue;
    }
    classes += 1;
    const klass = m[1];
    const table = tableFor(klass);
    for (const raw of splitParams(body)) {
      const text = raw.trim();
      if (!text) continue;
      const param = PARAM.exec(text);
      if (param === null) continue;
      const prop = param[1];
      const named = new RegExp(SERIAL_NAME.source).exec(text);
      const key = named ? named[1] : prop;
      if (named) serialsParsed += 1;
      keys.push({ klass, table, key, prop });
    }
  }
  if (classes === 0) problems.push(`${label}: no data class found — the denominator is absent`);
  const serialsRaw = [...stripComments(source).matchAll(SERIAL_NAME)].length;
  if (serialsRaw !== serialsParsed) {
    problems.push(
      `${label}: parsed ${serialsParsed} @SerialName keys but the file holds ` +
        `${serialsRaw} — the parser and the source disagree, so no key list from ` +
        "this run can be trusted",
    );
  }
  return { keys, problems };
}

/** `key =` anywhere: line-leading live key, commented key, or inline-table entry.
 *  The lookbehind keeps a shorter name from matching a longer one that starts with it. */
const keyToken = (key: string): RegExp => new RegExp(`(?<![\\w-])${escapeRe(key)}\\s*=`, "g");

/** `[providers.X.quirks.tool_surface]` as the disposition for the tool_surface key:
 *  the TOML-native way to document a sub-table. */
const tableHeader = (key: string): RegExp =>
  new RegExp(`^[ \\t]*\\[\\[?[^\\[\\]\\n]*\\.${escapeRe(key)}[ \\t]*\\]\\]?[ \\t]*$`, "gm");

/** Return (marker present, reason). An empty reason means the marker is present and
 *  the disposition is NOT. */
function retiredReason(text: string, key: string): { marked: boolean; reason: string } {
  const pattern = new RegExp(
    `^[ \\t]*#[ \\t]*retired:[ \\t]*${escapeRe(key)}(?![A-Za-z0-9_-])(.*)$`,
    "m",
  );
  const match = pattern.exec(text);
  if (match === null) return { marked: false, reason: "" };
  return { marked: true, reason: match[1].trim().replace(/^[—:-]+/, "").trim() };
}

/** key -> where it is documented, for `report`. Empty when nothing documents it. */
function dispositions(root: string): Map<string, string> {
  const sourcePath = join(root, SOURCE_REL);
  if (!existsSync(sourcePath)) return new Map();
  const { keys } = parseSource(readFileSync(sourcePath, "utf8"), SOURCE_REL);
  const texts = new Map<string, string>();
  for (const rel of SURFACES) {
    const p = join(root, rel);
    if (existsSync(p)) texts.set(rel, readFileSync(p, "utf8"));
  }
  const found = new Map<string, string>();
  for (const quirk of keys) {
    for (const [rel, text] of texts) {
      if (retiredReason(text, quirk.key).marked) found.set(quirk.key, `${rel}: retired`);
      else if (keyToken(quirk.key).test(text)) found.set(quirk.key, `${rel}: key`);
      else if (tableHeader(quirk.key).test(text)) found.set(quirk.key, `${rel}: table header`);
    }
  }
  return found;
}

function audit(root: string): string[] {
  const problems: string[] = [];
  const sourcePath = join(root, SOURCE_REL);
  if (!existsSync(sourcePath)) {
    return [`${SOURCE_REL}: missing — the quirk source IS the denominator, so its absence cannot pass`];
  }
  const { keys, problems: parseProblems } = parseSource(readFileSync(sourcePath, "utf8"), SOURCE_REL);
  problems.push(...parseProblems);
  if (keys.length === 0) {
    problems.push(
      `${SOURCE_REL}: parsed 0 quirk keys — refusing to pass vacuously, because a ` +
        "green over an empty denominator is what this wall exists to prevent",
    );
    return problems;
  }

  const texts = new Map<string, string>();
  for (const rel of SURFACES) {
    const p = join(root, rel);
    if (!existsSync(p)) {
      problems.push(`${rel}: disposition surface missing — a surface that cannot be read cannot document anything`);
      continue;
    }
    texts.set(rel, readFileSync(p, "utf8"));
  }

  for (const quirk of keys) {
    let documented = false;
    // attempted: a retirement marker was written for this key. It keeps an
    // unreasoned retirement to ONE problem — the specific, actionable one — rather
    // than also reporting the same key as undocumented.
    let attempted = false;
    for (const [rel, text] of texts) {
      const { marked, reason } = retiredReason(text, quirk.key);
      if (marked) {
        attempted = true;
        if (reason) {
          documented = true;
          continue;
        }
        problems.push(
          `${rel}: ${quirk.key} is retired with NO reason — a retirement ` +
            "without a written reason is an absence wearing a label",
        );
        continue;
      }
      if (keyToken(quirk.key).test(text) || tableHeader(quirk.key).test(text)) documented = true;
    }
    if (!documented && !attempted) {
      problems.push(
        `NO DISPOSITION: ${quirk.table}.${quirk.key} (${quirk.klass}.${quirk.prop}) ` +
          `is documented nowhere in ${SURFACES.join(" or ")}; document it there, or ` +
          `retire it with \`# retired: ${quirk.key} — <reason>\``,
      );
    }
  }
  return problems;
}

// ── selftest fixtures ─────────────────────────────────────────────────────────────────

const COMPLIANT_SOURCE = `package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class QuirksConfig(
    val store: Boolean = false,
    /** A KDoc between parameters, which a naive paren walk would choke on. */
    @SerialName("cache_key") val cacheKey: String = "first-message-hash",
    @SerialName("tool_surface") val toolSurface: ToolSurfaceConfig? = null,
)

@Serializable
public data class ToolSurfaceConfig(
    val enabled: Boolean = true,
    @SerialName("min_deferred") val minDeferred: Int = 8,
)
`;

const COMPLIANT_DOC = `[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
# The sub-table form is how TOML documents a nested table.
[providers.codex.quirks.tool_surface]
enabled = true
# min_deferred = 8
`;

const RETIRED_OK_DOC = `[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
[providers.codex.quirks.tool_surface]
enabled = true
# retired: min_deferred — folded into the surface defaults; the key is still parsed
`;

// The same tree with the reason deleted. The marker is present, the disposition is not.
const RETIRED_NOREASON_DOC = RETIRED_OK_DOC.replace(
  "# retired: min_deferred — folded into the surface defaults; the key is still parsed",
  "# retired: min_deferred —",
);

const RUNTIME_MAP_DOC = `[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
[providers.codex.quirks.tool_surface]
enabled = true
`;

const RUNTIME_MAP = `private fun shape(q: QuirksConfig) = buildJsonObject {
    put("min_deferred", q.toolSurface?.minDeferred)
}
`;

const EMPTY_SOURCE = `package splice.core.topology

public data class QuirksConfig()
`;

// A @SerialName outside any constructor: the comment stripper keeps it, the parser
// cannot attribute it, so the two counts disagree and the run must refuse.
const DRIFT_SOURCE =
  COMPLIANT_SOURCE +
  `
@SerialName("orphan")
public val orphan: String = "x"
`;

const FAKE_KEY_PARAM = '    @SerialName("fake_new_knob") val fakeNewKnob: Boolean? = null,\n)';

function writeTree(root: string, source: string, doc: string, emitter = ""): void {
  const src = join(root, SOURCE_REL);
  mkdirSync(dirname(src), { recursive: true });
  writeFileSync(src, source, "utf8");
  const s0 = join(root, SURFACES[0]);
  mkdirSync(dirname(s0), { recursive: true });
  writeFileSync(s0, doc, "utf8");
  const s1 = join(root, SURFACES[1]);
  mkdirSync(dirname(s1), { recursive: true });
  writeFileSync(s1, emitter, "utf8");
}

function selftest(): number {
  const failures: string[] = [];
  const root = join(tmpdir(), `quirks-keys-${process.pid}-${Math.random().toString(36).slice(2)}`);
  try {
    writeTree(root, COMPLIANT_SOURCE, COMPLIANT_DOC, RUNTIME_MAP);
    if (audit(root).length > 0) {
      failures.push(
        "compliant tree must be GREEN (key token, table header, commented key): " + audit(root).join("; "),
      );
    }
    const live = dispositions(root);
    if (live.size !== 5) {
      failures.push(`report must account for all 5 fixture keys, got ${live.size}: ${[...live.keys()].sort()}`);
    }

    // The mutation the row requires: a fake key appended to a temp copy of the source.
    const mutated = COMPLIANT_SOURCE.replace(
      '    @SerialName("tool_surface") val toolSurface: ToolSurfaceConfig? = null,\n)',
      '    @SerialName("tool_surface") val toolSurface: ToolSurfaceConfig? = null,\n' + FAKE_KEY_PARAM,
    );
    if (mutated === COMPLIANT_SOURCE) {
      failures.push("the mutation did not apply — the fake key never reached the temp copy");
    } else {
      writeTree(root, mutated, COMPLIANT_DOC);
      const hits = audit(root);
      if (!hits.some((h) => h.includes("fake_new_knob"))) {
        failures.push(`synthetic fake key must be RED BY NAME, got: ${hits}`);
      }
    }

    writeTree(root, COMPLIANT_SOURCE, RETIRED_OK_DOC);
    let hits = audit(root);
    if (hits.length > 0) {
      failures.push(`a reasoned retirement is a disposition, so the tree is GREEN, got: ${hits}`);
    }

    writeTree(root, COMPLIANT_SOURCE, RETIRED_NOREASON_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("min_deferred") && h.includes("NO reason"))) {
      failures.push(`a retirement with an empty reason must be RED by name, got: ${hits}`);
    }
    if (hits.filter((h) => h.includes("min_deferred")).length !== 1) {
      failures.push(`an unreasoned retirement is ONE problem, not a duplicate pair, got: ${hits}`);
    }

    writeTree(root, COMPLIANT_SOURCE, RUNTIME_MAP_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("NO DISPOSITION") && h.includes("min_deferred"))) {
      failures.push(`a key with no disposition at all must be RED by name, got: ${hits}`);
    }

    writeTree(root, COMPLIANT_SOURCE, RUNTIME_MAP);
    hits = audit(root);
    if (!hits.some((h) => h.includes("NO DISPOSITION") && h.includes("min_deferred"))) {
      failures.push(`a key named only in a runtime map is not documented, got: ${hits}`);
    }

    writeTree(root, EMPTY_SOURCE, COMPLIANT_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("refusing to pass vacuously"))) {
      failures.push(`a parse with no keys must be RED, got: ${hits}`);
    }

    writeTree(root, DRIFT_SOURCE, COMPLIANT_DOC);
    hits = audit(root);
    if (!hits.some((h) => h.includes("disagree"))) {
      failures.push(`a @SerialName the parser cannot attribute must be RED, got: ${hits}`);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }

  if (failures.length > 0) {
    process.stdout.write("quirks-keys-documented SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "quirks-keys-documented SELFTEST OK — a documented key, a table header and a " +
      "reasoned retirement are green; a synthetic key added to a temp source copy, an " +
      "unreasoned retirement, a key named only in a runtime map, a parse yielding no " +
      "keys, and a @SerialName count that disagrees with the file are all red by name\n",
  );
  return 0;
}

function report(root: string): void {
  const sourcePath = join(root, SOURCE_REL);
  const { keys, problems } = parseSource(readFileSync(sourcePath, "utf8"), SOURCE_REL);
  const live = dispositions(root);
  process.stdout.write(`quirks-keys-documented: ${keys.length} keys parsed from ${SOURCE_REL}\n`);
  for (const problem of problems) process.stdout.write(`  UNTRUSTED: ${problem}\n`);
  for (const quirk of keys) {
    const where = live.get(quirk.key) ?? "NO DISPOSITION";
    process.stdout.write(
      // The ORIGINAL's format spec bound to the LAST expression before it, so `{klass}.{prop:22s}`
      // padded prop ALONE: klass, then a dot, then prop padded to 22. Padding the concatenation
      // instead shifts every column right by the length of the class name — which is why this is
      // padEnd on prop and not on the joined string, and why the differential caught it.
      `  ${quirk.table}.${quirk.key.padEnd(24)} ${quirk.klass}.${quirk.prop.padEnd(22)} ${where}\n`,
    );
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
    process.stderr.write("quirks-keys-documented: tree missing\n");
    return 1;
  }
  if (argv.includes("report")) {
    report(root);
    return 0;
  }
  const problems = audit(root);
  if (problems.length > 0) {
    process.stdout.write("quirks-keys-documented RED:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    return 1;
  }
  process.stdout.write(
    "quirks-keys-documented GREEN: every quirk key in QuirksConfig.kt has a " +
      "disposition in config/splice.example.toml or the profile emitter\n",
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
