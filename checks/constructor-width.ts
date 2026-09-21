#!/usr/bin/env bun
/**
 * V4-93 — a primary constructor's WIDTH is billed, because detekt does not bill it.
 *
 * WHY THIS EXISTS. quality/detekt/detekt.yml:38-43 configures LongParameterList with
 * `constructorThreshold: 8`, and then turns it off for the two shapes this tree actually
 * uses:
 *
 *     LongParameterList:
 *       active: true
 *       functionThreshold: 6
 *       constructorThreshold: 8
 *       ignoreDefaultParameters: true      # every collaborator bundle defaults its seams
 *       ignoreDataClasses: true            # every config/wire type is a data class
 *
 * So the widest constructors in the tree are billed by NOTHING. Measured 2026-09-17:
 * HeadDeps takes 25 parameters, ResponsesQuirks 23, CodeModeRecord 22,
 * CodeModeRecordSnapshot 22, LaunchSpec 21, QuirksConfig 21, TurnMeta 19, TurnDrive 17,
 * ControlServer 17, ManagedHead 17 — fourteen constructors above twelve parameters, and
 * detekt reports zero LongParameterList findings on every one. That is not detekt being
 * wrong: the two ignores are there because a defaulted seam and a data-class field are
 * CHEAP individually. What they cannot see is the total, and the total is the coupling.
 *
 * TWO WIDTHS, because they fail differently.
 *   · PARAMETERS — more than MAX_PARAMS (12). A constructor this wide has no call site a
 *     reader can check: every argument is positional-or-named against a list nobody holds in
 *     their head, and adding one more is free. Data classes are IN SCOPE here, deliberately,
 *     against detekt's ignore: QuirksConfig and TurnMeta are exactly the shapes the ignore
 *     hides.
 *   · SUBSYSTEMS — more than MAX_SUBSYSTEMS (6) distinct `splice.*` packages named by the
 *     parameter TYPES. This is the architectural half, and it is the one a data class rarely
 *     trips: it counts how many parts of the system a single constructor has to know at once.
 *     HeadDeps names 8 (splice.core.model, splice.core.prompt, splice.core.util,
 *     splice.core.version, splice.gateway.compact, splice.gateway.perf, splice.gateway.usage,
 *     splice.spi); TurnDrive names 7. Same definition of `subsystem` as
 *     checks/concentration.ts — a distinct `splice.<pkg>` an import line resolves to — so the
 *     two oracles cannot disagree about what a subsystem is.
 * A constructor over EITHER width is an offender, named with the number that put it there.
 *
 * THE DENOMINATOR COMES FROM THE SOURCE (§24). Every `class` declaration with a primary
 * constructor under gateway/{module}/src/main is parsed on disk — top-level and nested, every
 * modifier spelling (`data`, `value`, `sealed`, `inner`, `annotation`, `private`). 746
 * constructors today. A class added tomorrow is in scope with no edit to this file. Three
 * guards refuse a vacuous pass: a parse yielding zero constructors FAILS, a class whose
 * constructor text cannot be walked FAILS by name, and `--selftest` re-derives the class
 * denominator from ast-grep's Kotlin AST so a spelling this file's regex misses cannot
 * disappear from both (the DR-51 `fun interface` dodge, one file over).
 *
 * WHY ITS OWN PARSER. The comment- and string-aware paren walk and the top-level comma split
 * are lifted in behaviour from checks/config/quirks-keys-documented.ts, which lifted them from
 * checks/config/shared-quirks-no-vendor-defaults.ts. That is the repo's existing idiom for this
 * job and the reason is the same: KDoc is interleaved BETWEEN parameters all over this tree
 * (HeadDeps has eight such blocks), so a naive paren walk either stops early or runs past the
 * constructor, and a naive comma split swallows every parameter after the first `Map<String,
 * QuirksConfig>`. The declaration matcher requires the paren to follow the class NAME (with
 * optional type parameters and an explicit `constructor` keyword), never merely to appear
 * somewhere on the line: `class X : Super(a, b, ...)` is a supertype call, and admitting it
 * would bill a class for arguments it passes rather than parameters it takes. Proven against
 * ast-grep's own `primary_constructor` nodes in selftest arm 13, count for count.
 *
 * THE RATCHET. Fourteen constructors are over the width today, so `12 or bust` cannot be the
 * gate leg without finishing the fix row first. What IS enforceable is the DIRECTION, in the
 * idiom checks/concentration.ts uses for the HIGH band and checks/public-surface.ts for the
 * public surface:
 *   · GROWTH fails — a constructor that crosses a width and is not recorded, or a RECORDED one
 *     that got WIDER than its recorded number, is red by name on the commit that does it. The
 *     second half matters more than the first: without it, HeadDeps could go from 25 to 40
 *     parameters under a green gate.
 *   · A STALE OR PADDED ENTRY fails — an entry naming a class that is gone, one that no longer
 *     offends, or one recorded ABOVE the measured width. A baseline held above the measurement
 *     is unearned room for the next regression to hide in; checks/concentration.ts records that
 *     exact defect twice (the 6.14 UpstreamClient ceiling against a file measuring 2.79).
 *   · IT CANNOT BE SATISFIED BY WEAKENING. Raising MAX_PARAMS would be a dated one-line diff
 *     reading as what it is; growing the baseline likewise. Shrinking it is the remedy the gate
 *     itself prints.
 *
 * NOT CAUGHT, stated here rather than discovered later.
 *   · SECONDARY constructors and factory functions. Only the PRIMARY constructor is measured.
 *     A 30-argument `constructor(...)` in a class body, or a `fun of(...)` taking the same
 *     bundle, is invisible here; detekt's functionThreshold 6 does bill plain functions, which
 *     is why the hole is narrow rather than wide.
 *   · A BUNDLE ONE LEVEL DOWN. Replacing 25 parameters with one `HeadDeps` parameter satisfies
 *     both widths without reducing coupling — the parameter count moves, the knowledge does
 *     not. That is the shape checks/concentration.ts's `concerns` term measures, and it is why
 *     these two oracles are read together rather than either alone.
 *   · A TYPE NAMED BY AN ALIAS OR A STAR IMPORT. Subsystems are resolved through the file's own
 *     single-type import lines, so a type reached by `import splice.x.*` or a typealias
 *     resolves to no subsystem and is simply not counted. Measured 2026-09-17: no splice
 *     package is star-imported anywhere in production, so the hole is empty today.
 *
 * SELFTEST. `--selftest` builds temp trees and proves BOTH directions plus the boring cases:
 * GREEN on a 12-parameter constructor, on a 6-subsystem one, on a class with no constructor and
 * on a recorded offender; RED BY NAME on a synthetic 13-parameter constructor (including one
 * spelled `data class` with every parameter defaulted — the exact pair detekt ignores), on a
 * 7-subsystem one, on a recorded offender that got wider, on a baseline entry recorded above the
 * measurement, on a stale entry, on an undated baseline, and on a tree whose parse yields no
 * constructors at all.
 *
 * Usage:
 *     bun checks/constructor-width.ts --ratchet [<root>]
 *     bun checks/constructor-width.ts --report [<root>]
 *     bun checks/constructor-width.ts --json [<root>]
 *     bun checks/constructor-width.ts --write-baseline [<root>]
 *     bun checks/constructor-width.ts --selftest
 *
 * A BARE RUN IS `--ratchet` — the one gating mode, so there is no non-gating default to
 * mis-invoke. The original script's bare form ran the REPORT, which exits 0 on a tree whose
 * ratchet plane has grown; that is the defect this port fixes, and the fix is that bare falls
 * through to the gate rather than to the report. `--write-baseline` rewrites the baseline and is
 * therefore never reachable by leaving a flag off a command line.
 */
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// parents[1]: this file lives at checks/, so the repo root is one level up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

const SRC_GLOB = "gateway/*/src/main";
const BASELINE_REL = "checks/config/constructor-width-baseline.json";

// The two widths. Deliberately visible constants: a threshold nobody can read is the same
// defect as the detekt ignores above.
const MAX_PARAMS = 12;
const MAX_SUBSYSTEMS = 6;

// V4-122 item 8': THE CONFIG-RECORD BUDGET, a THIRD width for a shape neither of the two above
// describes.
//
// WHY A CONFIG RECORD NEEDS ITS OWN BUDGET. This instrument exists to catch COLLABORATOR sprawl — a
// constructor whose width means it wires too many things and has no call site a human can read. A
// @Serializable record whose every parameter is a defaulted `val` and which names no subsystem is
// not that: its parameters are TOML KEYS. QuirksConfig carries twenty-five of them and every one is
// a vendor deformation the config surface has to name, already gated three other ways (the
// quirks-keys-documented wall, the oracle pin, and the disposition block in
// config/splice.example.toml). Measuring it against MAX_PARAMS made the row's own subject look like
// debt and produced a fix — group the keys to get back under 12-adjacent arithmetic — that would
// have cost a custom TOML serializer to buy one number. The code was never the debt; the instrument
// was counting a config surface as a dependency list.
//
// WHAT THIS BUDGET IS FOR, and what it is not. Its job on a config record is to catch a DUMPING
// GROUND — a record that has stopped being one vendor's quirks and become wherever new keys are
// thrown — not to cap a vendor surface, which grows one key per deformation by nature. 32 leaves
// QuirksConfig seven keys of room and still bites long before a dumping ground. A record that
// exceeds it should be SPLIT by subject, not compressed into fewer, wider keys.
const MAX_CONFIG_KEYS = 32;

// A class declaration whose name is followed (before any newline) by the primary-constructor
// paren. `^[ \t]*` admits nested classes; the modifier set admits every Kotlin spelling,
// including `annotation` and `value`, so the census cannot become a dodge list (DR-51).
const CLASS_DECL =
  /^[ \t]*(?:(?:public|internal|private|protected|sealed|data|abstract|open|value|enum|inner|annotation|expect|actual)[ \t]+)*class[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*(?:<[^<>\n]*>)?[ \t]*(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)\n]*\))?[ \t]*)*(?:(?:private|protected|internal|public)[ \t]+)*(?:constructor[ \t]*)?\(/gm;
// Same shape as checks/concentration.ts's SPLICE_IMPORT, so `subsystem` means one thing in
// this repo: the `splice.<pkg>` a single-type import line resolves to.
const SPLICE_IMPORT = /^import (splice\.[A-Za-z0-9_.]+)\.([A-Za-z0-9_]+)\s*$/gm;
const PARAM_NAME = /\b(?:val|var)?\s*([A-Za-z_][A-Za-z0-9_]*)\s*:/;
const TYPE_NAME = /\b([A-Z][A-Za-z0-9_]*)\b/g;
const RECORDED = /^\d{4}-\d{2}-\d{2}$/;

// ── the parser (behaviour lifted from checks/config/quirks-keys-documented.ts) ─────────

/** The primary-constructor text inside the parens at [start], or null.
 *
 *  Comment- and string-aware: KDoc is interleaved between parameters all over this tree, and
 *  a naive paren walk would either stop at the first `)` inside a default value or run past
 *  the constructor entirely. */
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

/** Python's str.isalnum(), which the `<` lookbehind below needs. */
const isAlnum = (ch: string): boolean => /[A-Za-z0-9]/.test(ch);

/** Split a constructor body on TOP-LEVEL commas, dropping comments.
 *
 *  Top-level is what makes `Map<String, QuotaTracker>` one parameter rather than two, and
 *  dropping comments is what keeps a KDoc sentence containing a comma from minting one. See
 *  ANGLE BRACKETS ARE NOT BRACKETS below for the lambda-default bug the lifted version had,
 *  and selftest arm 13 for the ast-grep count that found it. */
function splitParams(body: string): string[] {
  const parts: string[] = [];
  let buf: string[] = [];
  let depth = 0;
  let angle = 0;
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
    if ("({[".includes(ch)) {
      depth += 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (")}]".includes(ch)) {
      depth -= 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    // ANGLE BRACKETS ARE NOT BRACKETS (2026-09-17). Counting `<`/`>` as depth the way the
    // lifted parser does is correct for a pure-schema file and WRONG for Kotlin at large:
    // `->` and a `>` comparison each decremented the depth, so every parameter after a
    // lambda default fell out of the count. Measured against ast-grep's own
    // `class_parameter` nodes, eight constructors were UNDERCOUNTED — SessionRegistry read
    // 3 parameters where the grammar counts 7 — and undercounting is the direction that
    // hides an offender. A `<` only opens a type argument list when it IMMEDIATELY follows
    // an identifier (`Map<`), never across whitespace (`a < b`); a `>` only closes one when
    // a list is open and the character is not the tail of `->` or `>=`.
    if (
      ch === "<" &&
      i > 0 &&
      (isAlnum(body[i - 1]) || body[i - 1] === "_" || body[i - 1] === ">") &&
      body.slice(i + 1, i + 2) !== "="
    ) {
      angle += 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (ch === ">" && angle > 0 && body.slice(i - 1, i) !== "-" && body.slice(i + 1, i + 2) !== "=") {
      angle -= 1;
      buf.push(ch);
      i += 1;
      continue;
    }
    if (ch === "," && depth === 0 && angle === 0) {
      parts.push(buf.join(""));
      buf = [];
      i += 1;
      continue;
    }
    buf.push(ch);
    i += 1;
  }
  if (buf.length > 0) parts.push(buf.join(""));
  return parts.map((raw) => raw.trim()).filter((part) => part);
}

class Constructor {
  readonly name: string;
  readonly rel: string;
  readonly line: number;
  readonly params: number;
  readonly subsystems: string[];
  readonly configRecord: boolean;

  constructor(
    name: string,
    rel: string,
    line: number,
    params: number,
    subsystems: string[],
    configRecord = false,
  ) {
    this.name = name;
    this.rel = rel;
    this.line = line;
    this.params = params;
    this.subsystems = subsystems;
    this.configRecord = configRecord;
  }

  /** Baseline identity: the class name plus its file. Two classes in this tree share a
   *  simple name often enough (`Success`, `Nested`) that the name alone is not an identity,
   *  and the file is what makes a moved class read as a move rather than as a new offender. */
  get id(): string {
    return `${this.rel} ${this.name}`;
  }

  over(): string[] {
    // A CONFIG RECORD is measured against the config budget ALONE. It cannot reach the other two
    // by construction — no subsystem is part of its definition — so grading it on params would
    // be grading TOML keys against a collaborator limit, which is the defect this shape fixes.
    if (this.configRecord) {
      if (this.params > MAX_CONFIG_KEYS) {
        return [`${this.params} config keys (max ${MAX_CONFIG_KEYS})`];
      }
      return [];
    }
    const reasons: string[] = [];
    if (this.params > MAX_PARAMS) {
      reasons.push(`${this.params} parameters (max ${MAX_PARAMS})`);
    }
    if (this.subsystems.length > MAX_SUBSYSTEMS) {
      reasons.push(
        `${this.subsystems.length} subsystems (max ${MAX_SUBSYSTEMS}): ${this.subsystems.join(", ")}`,
      );
    }
    return reasons;
  }
}

// A parameter that is a `val` WITH a default — annotations allowed in front. `[^=]*` is the type,
// so the `=` it must reach is the default's, never an `=` inside a type expression.
const DEFAULTED_VAL =
  /^\s*(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)\n]*\))?\s*)*val\s+[A-Za-z_][A-Za-z0-9_]*\s*:[^=]*=/;

/** A CONFIG RECORD — the shape [MAX_CONFIG_KEYS] governs. All three clauses are required, and
 *  each is doing work rather than decorating a heuristic:
 *
 *  @Serializable — the class is a wire or config SURFACE, which is what the annotation says. A
 *  plain class with the same shape is a value object and is graded at the ordinary widths; that is
 *  the property the selftest's arm B pins, because without it the exemption would be reachable by
 *  deleting one annotation.
 *
 *  EVERY parameter a defaulted `val` — a key the config may omit. One parameter without a default
 *  means the class has a REQUIRED collaborator, so it is wiring something and the ordinary budgets
 *  are the right ones. `var` is excluded for the same reason: mutable state is not a key.
 *
 *  NO SUBSYSTEM — a record that names a `splice.*` type is wiring that type, whatever else it looks
 *  like. */
function isConfigRecord(text: string, classStart: number, params: string[], subsystems: string[]): boolean {
  if (subsystems.length > 0 || params.length === 0) return false;
  if (!params.every((param) => DEFAULTED_VAL.test(param))) return false;
  const above = text.slice(0, classStart).replace(/\n+$/, "").split("\n");
  for (let i = above.length - 1; i >= 0; i -= 1) {
    const stripped = above[i].trim();
    if (!stripped.startsWith("@")) break;
    if (stripped.startsWith("@Serializable")) return true;
  }
  return false;
}

function measureFile(rel: string, text: string): { found: Constructor[]; problems: string[] } {
  const problems: string[] = [];
  const imports = new Map<string, string>();
  for (const m of text.matchAll(SPLICE_IMPORT)) imports.set(m[2], m[1]);
  const found: Constructor[] = [];
  for (const match of text.matchAll(CLASS_DECL)) {
    const at = match.index as number;
    const body = extractConstructor(text, at + match[0].length - 1);
    const line = text.slice(0, at).split("\n").length;
    if (body === null) {
      problems.push(
        `${rel}:${line}: ${match[1]}'s primary constructor could not be walked — ` +
          "no width from this run can be trusted",
      );
      continue;
    }
    const params = splitParams(body);
    if (params.length === 0) continue;
    const subsystems = [
      ...new Set(
        params.flatMap((param) =>
          [...param.matchAll(TYPE_NAME)].map((m) => m[1]).filter((name) => imports.has(name)),
        ).map((name) => imports.get(name) as string),
      ),
    ].sort();
    const configRecord = isConfigRecord(text, at, params, subsystems);
    found.push(new Constructor(match[1], rel, line, params.length, subsystems, configRecord));
  }
  return { found, problems };
}

function collect(root: string): { constructors: Constructor[]; problems: string[] } {
  const constructors: Constructor[] = [];
  const problems: string[] = [];
  const files = [...new Bun.Glob(`${SRC_GLOB}/**/*.kt`).scanSync({ cwd: root, followSymlinks: true })].sort();
  for (const rel of files) {
    const measured = measureFile(rel, readFileSync(join(root, rel), "utf8"));
    constructors.push(...measured.found);
    problems.push(...measured.problems);
  }
  if (constructors.length === 0) {
    problems.push(
      `parsed 0 primary constructors under ${SRC_GLOB} — refusing to pass vacuously, because ` +
        "a green over an empty denominator is what this wall exists to prevent",
    );
  }
  return { constructors, problems };
}

function offendersOf(constructors: Constructor[]): Constructor[] {
  return constructors
    .filter((c) => c.over().length > 0)
    .sort((a, b) => {
      if (a.params !== b.params) return b.params - a.params;
      if (a.subsystems.length !== b.subsystems.length) return b.subsystems.length - a.subsystems.length;
      return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
}

// ── the baseline ──────────────────────────────────────────────────────────────────────

const BASELINE_LAW =
  "V4-93 ratchet. One entry per primary constructor already over a width on the recorded date, " +
  "with the numbers MEASURED that day. The gate fails when an unrecorded constructor crosses " +
  `${MAX_PARAMS} parameters or ${MAX_SUBSYSTEMS} subsystems, when a recorded one gets WIDER than ` +
  "its entry, when an entry is recorded ABOVE the measurement (padding), and when an entry stops " +
  "offending or names a class that is gone. Re-measure with " +
  "`bun checks/constructor-width.ts --write-baseline`; the diff is the record of what moved.";

/** Python's repr() of a string: 'a' — used in the messages quoted back to the operator. */
const pyRepr = (value: string): string => `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;

function readBaseline(root: string): {
  entries: Record<string, { params?: unknown; subsystems?: unknown }>;
  recorded: string;
  problems: string[];
} {
  const path = join(root, BASELINE_REL);
  if (!existsSync(path)) {
    return {
      entries: {},
      recorded: "",
      problems: [
        `${BASELINE_REL}: missing — the ratchet has no baseline to grade against. Write one with ` +
          "`bun checks/constructor-width.ts --write-baseline`.",
      ],
    };
  }
  let data: Record<string, unknown>;
  try {
    data = JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
  } catch (error) {
    return {
      entries: {},
      recorded: "",
      problems: [
        `${BASELINE_REL}: is not valid JSON (${error}) — a baseline nobody can parse grades nothing`,
      ],
    };
  }
  const recorded = String(data.recorded ?? "");
  const problems: string[] = [];
  if (!RECORDED.test(recorded)) {
    problems.push(
      `${BASELINE_REL}: \`recorded\` is ${pyRepr(recorded)} — every baseline carries the ISO date it was ` +
        "measured, exactly as checks/concentration.ts's RATCHET_RECORDED does; an undated baseline " +
        "is how the next regression hides.",
    );
  }
  const entries = data.offenders;
  if (entries === null || typeof entries !== "object" || Array.isArray(entries)) {
    problems.push(`${BASELINE_REL}: \`offenders\` must be an object keyed '<file> <ClassName>'`);
    return { entries: {}, recorded, problems };
  }
  for (const [key, value] of Object.entries(entries as Record<string, Record<string, unknown>>)) {
    if (
      value === null ||
      typeof value !== "object" ||
      !Number.isInteger(value.params) ||
      !Number.isInteger(value.subsystems)
    ) {
      problems.push(
        `${BASELINE_REL}: entry ${pyRepr(key)} must carry integer \`params\` and \`subsystems\` — an entry ` +
          "with no measured numbers records nothing and can neither grow nor shrink",
      );
    }
  }
  return { entries: entries as Record<string, { params?: unknown; subsystems?: unknown }>, recorded, problems };
}

/** Python's json.dumps(value, indent=2): ensure_ascii escapes every non-ASCII code unit. */
function pyJsonDumps(value: unknown): string {
  const text = JSON.stringify(value, null, 2);
  const parts: string[] = [];
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i);
    parts.push(code > 127 ? "\\u" + code.toString(16).padStart(4, "0") : text[i]);
  }
  return parts.join("");
}

function writeBaseline(root: string, offenders: Constructor[], today: string): void {
  const path = join(root, BASELINE_REL);
  mkdirSync(dirname(path), { recursive: true });
  const entries: Record<string, { params: number; subsystems: number }> = {};
  for (const c of [...offenders].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))) {
    entries[c.id] = { params: c.params, subsystems: c.subsystems.length };
  }
  writeFileSync(
    path,
    pyJsonDumps({
      recorded: today,
      max_params: MAX_PARAMS,
      max_subsystems: MAX_SUBSYSTEMS,
      law: BASELINE_LAW,
      offenders: entries,
    }) + "\n",
    "utf8",
  );
}

/** The local ISO date, matching Python's datetime.date.today().isoformat(). */
function todayIso(): string {
  const now = new Date();
  const pad = (n: number): string => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function ratchet(root: string): { code: number; out: string; err: string } {
  const out: string[] = [];
  const err: string[] = [];
  const { constructors, problems: collectProblems } = collect(root);
  const offenders = offendersOf(constructors);
  const { entries: baseline, recorded, problems: baselineProblems } = readBaseline(root);
  const problems = [...collectProblems, ...baselineProblems];
  const measured = new Map<string, Constructor>(offenders.map((c) => [c.id, c]));

  out.push(`CONSTRUCTOR WIDTH RATCHET — baseline recorded ${recorded || "(none)"}`);
  out.push(`  ${"primary constructors".padEnd(30)} measured ${String(constructors.length).padStart(4)}`);
  out.push(
    `  ${`over ${MAX_PARAMS} params / ${MAX_SUBSYSTEMS} subsystems`.padEnd(30)} ` +
      `measured ${String(measured.size).padStart(4)}   baseline ${String(Object.keys(baseline).length).padStart(4)}   [GATED]`,
  );
  for (const c of offenders) {
    out.push(
      `    ${c.name.padEnd(26)} params ${String(c.params).padStart(3)}  ` +
        `subsystems ${String(c.subsystems.length).padStart(2)}  ${c.rel}:${c.line}`,
    );
  }

  const measuredKeys = new Set(measured.keys());
  const baselineKeys = new Set(Object.keys(baseline));
  for (const key of [...measuredKeys].filter((k) => !baselineKeys.has(k)).sort()) {
    const c = measured.get(key) as Constructor;
    problems.push(
      `GROWTH: ${c.rel}:${c.line} ${c.name} is over a constructor width and nothing records it — ` +
        `${c.over().join("; ")}. detekt cannot see this (quality/detekt/detekt.yml:38-43 ignores data ` +
        `classes and defaulted parameters), which is why this wall exists. Take the bundle apart, ` +
        `or record it with \`bun checks/constructor-width.ts --write-baseline\` — a dated diff ` +
        `saying the tree got wider.`,
    );
  }
  for (const key of [...measuredKeys].filter((k) => baselineKeys.has(k)).sort()) {
    const c = measured.get(key) as Constructor;
    const was = baseline[key];
    if (was === null || typeof was !== "object" || !Number.isInteger(was.params)) continue;
    const wasParams = was.params as number;
    const wasSubsystems = was.subsystems as number;
    if (c.params > wasParams || c.subsystems.length > wasSubsystems) {
      problems.push(
        `WIDENED: ${c.rel}:${c.line} ${c.name} grew past its recorded width — params ` +
          `${wasParams} -> ${c.params}, subsystems ${wasSubsystems} -> ${c.subsystems.length}. ` +
          `A recorded offender is DEBT, not permission to keep adding parameters.`,
      );
    } else if (c.params < wasParams || c.subsystems.length < wasSubsystems) {
      problems.push(
        `PADDED: ${c.rel}:${c.line} ${c.name} measures params ${c.params} / subsystems ` +
          `${c.subsystems.length} but its entry records ${wasParams} / ${wasSubsystems}. ` +
          `Re-measure with \`--write-baseline\`. A baseline held above the measurement is ` +
          `unearned room for the next regression to hide in — the same defect as a ceiling ` +
          `recorded above its file's measured ratio.`,
      );
    }
  }
  for (const key of [...baselineKeys].filter((k) => !measuredKeys.has(k)).sort()) {
    problems.push(
      `STALE: the baseline lists ${pyRepr(key)}, which is no longer over any width (taken apart, ` +
        `renamed, or deleted) — drop the entry with \`--write-baseline\`, so the list keeps meaning ` +
        `'known debt'.`,
    );
  }

  if (problems.length > 0) {
    err.push(`\nFAIL: constructor-width ratchet — ${problems.length} problem(s):`);
    for (const problem of problems) err.push("  x " + problem);
    // The FAIL block goes to stderr, so stdout ends at the LAST OFFENDER LINE — and Python's
    // print() gave that line a terminating newline. Joining without one leaves stdout one byte
    // short of the original on every red path, which is exactly where a differential is not
    // looking if it only ever runs the clean tree.
    return { code: 1, out: out.join("\n") + "\n", err: err.join("\n") + "\n" };
  }
  out.push(
    `\nOK: constructor-width ratchet holds — the ${measured.size} wide constructor(s) are exactly ` +
      `the ${recorded} baseline, none has widened, and nothing listed there has stopped offending`,
  );
  return { code: 0, out: out.join("\n") + "\n", err: "" };
}

function report(root: string): { code: number; out: string } {
  const out: string[] = [];
  const { constructors, problems } = collect(root);
  for (const problem of problems) out.push("  UNTRUSTED: " + problem);
  const offenders = offendersOf(constructors);
  out.push(
    `constructor-width: ${constructors.length} primary constructor(s), ${offenders.length} over ` +
      `${MAX_PARAMS} parameters or ${MAX_SUBSYSTEMS} subsystems`,
  );
  for (const c of offenders) {
    out.push(
      `  ${c.name.padEnd(26)} params ${String(c.params).padStart(3)}  ` +
        `subsystems ${String(c.subsystems.length).padStart(2)}  ${c.rel}:${c.line}`,
    );
    for (const reason of c.over()) out.push(`      over: ${reason}`);
  }
  return { code: problems.length > 0 ? 1 : 0, out: out.join("\n") + "\n" };
}

// ── selftest ──────────────────────────────────────────────────────────────────────────

function ktParams(n: number, opts: { data: boolean; defaulted: boolean }): string {
  const kind = opts.data ? "data class" : "class";
  const tail = opts.defaulted ? " = 0" : "";
  const body = Array.from({ length: n }, (_, i) => `    val p${i}: Int${tail}`).join(",\n");
  return `package splice.selftest\n\npublic ${kind} Selftest${opts.data ? "Data" : "Plain"}${n}(\n${body},\n)\n`;
}

function ktSubsystems(n: number): string {
  const imports = Array.from({ length: n }, (_, i) => `import splice.sub${i}.Type${i}`).join("\n");
  const params = Array.from({ length: n }, (_, i) => `    val p${i}: Type${i}`).join(",\n");
  return `package splice.selftest\n\n${imports}\n\npublic class SelftestWide${n}(\n${params},\n)\n`;
}

function writeKt(root: string, rel: string, text: string): void {
  const path = join(root, "gateway", "zz-selftest", "src", "main", "kotlin", rel);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text, "utf8");
}

function baselineFixture(
  root: string,
  entries: Record<string, { params: number; subsystems: number }>,
  recorded = "2026-09-17",
): void {
  const path = join(root, BASELINE_REL);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, pyJsonDumps({ recorded, law: BASELINE_LAW, offenders: entries }) + "\n", "utf8");
}

const SELFTEST_REL = "gateway/zz-selftest/src/main/kotlin/Fixture.kt";

function mkdtemp(): string {
  const dir = join(tmpdir(), `constructor-width-${process.pid}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(dir, { recursive: true });
  return dir;
}

function selftest(): number {
  const failures: string[] = [];

  const arm = (label: string, build: (root: string) => void, expectRed: string | null): void => {
    const root = mkdtemp();
    try {
      build(root);
      const { code, out, err } = ratchet(root);
      const text = out + err;
      if (expectRed === null) {
        if (code !== 0) {
          failures.push(`${label} — must be GREEN, got exit ${code}: ${text.trim().slice(0, 240)}`);
        }
      } else if (code === 0) {
        failures.push(`${label} — MUST be RED, exited 0: ${text.trim().slice(0, 240)}`);
      } else if (!text.includes(expectRed)) {
        failures.push(
          `${label} — red for the wrong reason (expected ${pyRepr(expectRed)}): ${text.trim().slice(0, 240)}`,
        );
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  };

  // 1. exactly at the limits: 12 parameters, 6 subsystems. GREEN, and that is the boundary.
  const atLimit = (root: string): void => {
    writeKt(root, "Fixture.kt", ktParams(MAX_PARAMS, { data: true, defaulted: true }));
    writeKt(root, "Wide.kt", ktSubsystems(MAX_SUBSYSTEMS));
    baselineFixture(root, {});
  };
  arm(`1. ${MAX_PARAMS} parameters and ${MAX_SUBSYSTEMS} subsystems are AT the limit, not over`, atLimit, null);

  // 2. THE MUTATION THIS ROW REQUIRES, in the exact shape detekt ignores: a data class whose
  //    every parameter is defaulted. One parameter over.
  const oneOver = (root: string): void => {
    writeKt(root, "Fixture.kt", ktParams(MAX_PARAMS + 1, { data: true, defaulted: true }));
    baselineFixture(root, {});
  };
  arm("2. GROWTH — a 13-parameter defaulted DATA class (detekt's two ignores together)", oneOver, "GROWTH");
  {
    const root = mkdtemp();
    try {
      oneOver(root);
      const { constructors } = collect(root);
      const hit = offendersOf(constructors).filter((c) => c.name.endsWith(String(MAX_PARAMS + 1)));
      if (hit.length === 0) {
        failures.push(
          `2. the synthetic wide class must be named: ${offendersOf(constructors).map((c) => c.name)}`,
        );
      } else if (hit[0].params !== MAX_PARAMS + 1) {
        failures.push(`2. the parameter count must be exact, got ${hit[0].params}`);
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  }

  // 3. one subsystem over, with the parameter count comfortably inside.
  const subsystemOver = (root: string): void => {
    writeKt(root, "Wide.kt", ktSubsystems(MAX_SUBSYSTEMS + 1));
    baselineFixture(root, {});
  };
  arm("3. GROWTH — 7 subsystems in a 7-parameter constructor", subsystemOver, "subsystems");

  // 4. the same tree with the offender RECORDED at its measured width: the ratchet holds.
  const recordedOk = (root: string): void => {
    oneOver(root);
    baselineFixture(root, {
      [`${SELFTEST_REL} SelftestData${MAX_PARAMS + 1}`]: { params: MAX_PARAMS + 1, subsystems: 0 },
    });
  };
  arm("4. a RECORDED offender at its measured width is not growth", recordedOk, null);

  // 5. a recorded offender that got WIDER — the arm without which a baseline is a licence.
  const widened = (root: string): void => {
    writeKt(root, "Fixture.kt", ktParams(MAX_PARAMS + 5, { data: true, defaulted: true }));
    baselineFixture(root, {
      [`${SELFTEST_REL} SelftestData${MAX_PARAMS + 5}`]: { params: MAX_PARAMS + 1, subsystems: 0 },
    });
  };
  arm("5. WIDENED — a recorded offender that grew past its entry", widened, "WIDENED");

  // 6. an entry recorded ABOVE the measurement is padding, and padding fails.
  const padded = (root: string): void => {
    oneOver(root);
    baselineFixture(root, {
      [`${SELFTEST_REL} SelftestData${MAX_PARAMS + 1}`]: { params: 99, subsystems: 0 },
    });
  };
  arm("6. PADDED — an entry recorded above the measured width", padded, "PADDED");

  // 7. a stale entry — the class is gone.
  const stale = (root: string): void => {
    atLimit(root);
    baselineFixture(root, {
      "gateway/zz-selftest/src/main/kotlin/Gone.kt WasWideOnce": { params: 20, subsystems: 0 },
    });
  };
  arm("7. STALE — an entry naming a constructor that is no longer wide", stale, "STALE");

  // 8. an undated baseline is a hard error, not a pass.
  const undated = (root: string): void => {
    atLimit(root);
    baselineFixture(root, {}, "");
  };
  arm("8. an undated baseline is a hard error", undated, "recorded");

  // 9. THE BORING CASES (§24), which are the ones that get waved through.
  const empty = (root: string): void => {
    baselineFixture(root, {});
  };
  arm("9. a tree with no constructors at all must REFUSE, not pass vacuously", empty, "vacuously");

  const noConstructor = (root: string): void => {
    writeKt(
      root,
      "Fixture.kt",
      "package splice.selftest\n\npublic class SelftestNoCtor\npublic object SelftestObject\n",
    );
    baselineFixture(root, {});
  };
  arm(
    "10. a class with no primary constructor is out of the denominator, not an offender",
    noConstructor,
    "vacuously",
  );

  const oneConstructor = (root: string): void => {
    writeKt(root, "Fixture.kt", "package splice.selftest\n\npublic class SelftestOne(val p0: Int)\n");
    baselineFixture(root, {});
  };
  arm("11. the one-item tree grades green WITH its count", oneConstructor, null);

  // 12. KDoc between parameters must not break the walk — the reason this file has its own parser.
  {
    const root = mkdtemp();
    try {
      writeKt(
        root,
        "Fixture.kt",
        "package splice.selftest\n\nimport splice.sub0.Type0\n\n" +
          "public data class SelftestKdoc(\n" +
          "    val a: Type0,\n" +
          "    /** A KDoc, with a comma and a ) in it, between parameters. */\n" +
          '    val b: Map<String, Int> = mapOf("x" to 1),\n' +
          "    val c: Int = 0,\n" +
          ")\n",
      );
      const { constructors, problems } = collect(root);
      const found = constructors.filter((c) => c.name === "SelftestKdoc");
      if (problems.length > 0 || found.length === 0) {
        failures.push(`12. KDoc arm — the constructor must parse: ${problems}`);
      } else if (found[0].params !== 3 || found[0].subsystems.join(",") !== "splice.sub0") {
        failures.push(
          `12. KDoc arm — want 3 params / ['splice.sub0'], got ${found[0].params} / ` +
            `[${found[0].subsystems.map(pyRepr).join(", ")}]`,
        );
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  }

  // 13. THE DENOMINATOR FROM OUTSIDE THIS FILE'S REGEXES. Every class_declaration ast-grep
  //     finds in production whose text opens a primary-constructor paren must be seen by
  //     CLASS_DECL — so a spelling the regex misses cannot disappear from both (DR-51).
  failures.push(...astDenominator());

  if (failures.length > 0) {
    process.stdout.write("constructor-width SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  x " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "constructor-width SELFTEST OK — the limits themselves, a recorded offender, a class with no " +
      "constructor and an interleaved KDoc are green; a 13-parameter defaulted data class, a " +
      "7-subsystem constructor, a widened entry, a padded entry, a stale entry, an undated baseline " +
      "and an empty denominator are all red; and the class census agrees with ast-grep's AST\n",
  );
  return 0;
}

/** Every primary constructor ast-grep's Kotlin grammar finds must be measured, with the SAME
 *  parameter count.
 *
 *  THE DENOMINATOR FROM OUTSIDE THIS FILE (§24). A regex checked against its own output cannot
 *  fail for a spelling it does not admit — that is exactly how `fun interface` went 92 files
 *  unbilled in checks/concentration.ts (DR-51), and how a `@Suppress`-annotated or
 *  explicit-`constructor` declaration would go unbilled here. tree-sitter-kotlin has a
 *  `primary_constructor` node, so the census is graded against the GRAMMAR, and `class_parameter`
 *  nodes give the parameter count a second, independent time. checks/concentration-selftest.sh
 *  arm 7b does the same thing for annotation classes. */
function astDenominator(): string[] {
  const which = spawnSync("sh", ["-c", "command -v ast-grep"], { encoding: "utf8" });
  if (which.status !== 0 || !which.stdout.trim()) {
    return ["13. AST denominator — ast-grep is unavailable, so the external denominator cannot run"];
  }
  // RELATIVE targets with cwd=ROOT, so ast-grep emits the same repo-relative paths
  // collect() records — an absolute-vs-relative mismatch would read as "the regex misses
  // every constructor in the tree", which is a loud failure but the wrong one.
  const targets = [...new Bun.Glob(SRC_GLOB).scanSync({ cwd: ROOT, onlyFiles: false })]
    .filter((rel) => existsSync(join(ROOT, rel)))
    .sort();
  if (targets.length === 0) return ["13. AST denominator — no production source roots found"];

  const nodes = (kind: string): { ok: true; nodes: AstNode[] } | { ok: false; why: string } => {
    const result = spawnSync(
      "ast-grep",
      ["run", "--kind", kind, "--lang", "kotlin", "--json=compact", ...targets],
      { cwd: ROOT, encoding: "utf8", maxBuffer: 256 * 1024 * 1024 },
    );
    if (result.status !== 0) {
      return { ok: false, why: `ast-grep --kind ${kind} failed: ${(result.stderr || "").trim().slice(0, 200)}` };
    }
    return { ok: true, nodes: JSON.parse(result.stdout || "[]") as AstNode[] };
  };

  const ctorsResult = nodes("primary_constructor");
  if (!ctorsResult.ok) return [`13. AST denominator — ${ctorsResult.why}`];
  const paramsResult = nodes("class_parameter");
  if (!paramsResult.ok) return [`13. AST denominator — ${paramsResult.why}`];
  const ctors = ctorsResult.nodes;
  const params = paramsResult.nodes;
  if (ctors.length === 0) {
    return ["13. AST denominator — zero AST primary constructors; refusing a vacuous pass"];
  }

  // A class_parameter can only occur inside a primary constructor, so byte containment is an
  // unambiguous attribution rather than a heuristic.
  const spans: [string, number, number, number][] = ctors.map((node) => [
    node.file,
    node.range.byteOffset.start,
    node.range.byteOffset.end,
    node.range.start.line + 1,
  ]);
  const astCount = new Map<string, number>();
  for (const param of params) {
    const start = param.range.byteOffset.start;
    for (const [file, lo, hi, line] of spans) {
      if (param.file === file && lo <= start && start < hi) {
        const key = `${file} ${line}`;
        astCount.set(key, (astCount.get(key) ?? 0) + 1);
        break;
      }
    }
  }

  const { constructors: measured, problems } = collect(ROOT);
  if (problems.length > 0) {
    return [`13. AST denominator — the tree measurement is untrusted: ${problems[0]}`];
  }
  const mine = new Map<string, Constructor>(measured.map((c) => [`${c.rel} ${c.line}`, c]));
  const missed: string[] = [];
  const miscounted: string[] = [];
  for (const [file, , , line] of spans) {
    const key = `${file} ${line}`;
    const count = astCount.get(key) ?? 0;
    if (count === 0) {
      // A no-parameter primary constructor (`class X()`) is deliberately out of the
      // denominator here and in collect(); nothing to compare.
      continue;
    }
    const seen = mine.get(key);
    if (seen === undefined) missed.push(`${file}:${line}`);
    else if (seen.params !== count) {
      miscounted.push(`${file}:${line} AST ${count} vs measured ${seen.params}`);
    }
  }
  const out: string[] = [];
  if (missed.length > 0) {
    out.push(
      `13. AST denominator — ${missed.length} AST primary constructor(s) are invisible to ` +
        `CLASS_DECL, so their width is billed by nothing: ${pyReprList(missed.slice(0, 6))}`,
    );
  }
  if (miscounted.length > 0) {
    out.push(
      `13. AST parameter count — ${miscounted.length} constructor(s) disagree with the grammar: ` +
        `${pyReprList(miscounted.slice(0, 6))}`,
    );
  }
  if (out.length === 0) {
    const spanKeys = new Set(spans.map(([file, , , line]) => `${file} ${line}`));
    const extra = [...mine.entries()].filter(([key]) => !spanKeys.has(key)).map(([, c]) => `${c.rel}:${c.line}`);
    if (extra.length > 0) {
      out.push(
        `13. AST denominator — CLASS_DECL invents ${extra.length} constructor(s) the grammar ` +
          `does not have (a supertype call read as a primary constructor): ${pyReprList(extra.slice(0, 6))}`,
      );
    }
  }
  return out;
}

interface AstNode {
  file: string;
  range: { byteOffset: { start: number; end: number }; start: { line: number } };
}

/** Python's repr() of a list of strings: `['a', 'b']`. */
function pyReprList(items: string[]): string {
  return `[${items.map(pyRepr).join(", ")}]`;
}

const USAGE = `usage: constructor-width [--ratchet | --report | --json | --write-baseline | --selftest] [<root>]
  --ratchet         gate leg: growth, widening, padding and stale entries (what a bare run does)
  --report          the offender inventory, no baseline and no gating
  --json            the same census as JSON
  --write-baseline  re-measure and rewrite the baseline (never reachable by a bare run)
  --selftest        red-green proof, out of tree
  <root>            tree to measure (default: the repo root)
`;

function main(argv: string[]): number {
  let ratchetMode = false;
  let reportMode = false;
  let writeBaselineMode = false;
  let selftestMode = false;
  let jsonMode = false;
  let rootArg: string | null = null;

  for (const arg of argv) {
    if (arg === "--ratchet") ratchetMode = true;
    else if (arg === "--report") reportMode = true;
    else if (arg === "--write-baseline") writeBaselineMode = true;
    else if (arg === "--selftest") selftestMode = true;
    else if (arg === "--json") jsonMode = true;
    else if (arg.startsWith("-")) {
      process.stderr.write(`constructor-width: unrecognised argument '${arg}'\n${USAGE}`);
      return 2;
    } else if (rootArg === null) rootArg = arg;
    else {
      process.stderr.write(`constructor-width: unexpected extra argument '${arg}'\n${USAGE}`);
      return 2;
    }
  }

  if (selftestMode) return selftest();
  const root = rootArg === null ? ROOT : realpathSync(rootArg);
  if (!existsSync(root)) {
    process.stderr.write(`constructor-width: ${root} does not exist\n`);
    return 2;
  }

  // A bare run is the GATE. The original ran the report here, which reads as a pass on a tree
  // whose ratchet plane has grown; that is the defect this port fixes.
  if (!ratchetMode && !reportMode && !writeBaselineMode && !jsonMode) ratchetMode = true;

  if (writeBaselineMode) {
    const { constructors, problems } = collect(root);
    if (problems.length > 0) {
      process.stderr.write("refusing to write a baseline from an untrusted measurement:\n");
      for (const problem of problems) process.stderr.write("  x " + problem + "\n");
      return 2;
    }
    const today = todayIso();
    const offenders = offendersOf(constructors);
    writeBaseline(root, offenders, today);
    process.stdout.write(
      `wrote ${BASELINE_REL}: ${offenders.length} wide constructor(s) of ${constructors.length}, ` +
        `recorded ${today}. READ THE DIFF — it is the record of what moved.\n`,
    );
    return 0;
  }

  if (jsonMode) {
    const { constructors, problems } = collect(root);
    process.stdout.write(
      pyJsonDumps({
        constructors: constructors.length,
        max_params: MAX_PARAMS,
        max_subsystems: MAX_SUBSYSTEMS,
        problems,
        offenders: offendersOf(constructors).map((c) => ({
          name: c.name,
          file: c.rel,
          line: c.line,
          params: c.params,
          subsystems: c.subsystems,
        })),
      }) + "\n",
    );
    return problems.length > 0 ? 1 : 0;
  }

  if (reportMode) {
    const { code, out } = report(root);
    process.stdout.write(out);
    return code;
  }

  const { code, out, err } = ratchet(root);
  if (out) process.stdout.write(out);
  if (err) process.stderr.write(err);
  return code;
}

process.exit(main(process.argv.slice(2)));
