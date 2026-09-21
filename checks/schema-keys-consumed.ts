#!/usr/bin/env bun
/**
 * V4-91 — every config key an operator can write must be ACTED ON somewhere, by name.
 *
 * WHY THIS EXISTS. A key that parses is not a key that works. `[daemon] state_dir` has been in
 * the schema since the port (core/src/main/kotlin/splice/core/topology/Topology.kt:75,
 * `@SerialName("state_dir") val stateDir: String?`), it deserializes cleanly, it is echoed back
 * by `doctor --json`, and NOTHING reads it: the state directory comes from
 * CLAUDEX_STATE_DIR or the default in
 * core/src/main/kotlin/splice/core/config/StatePaths.kt:15-16, never from the TOML. An
 * operator who sets it gets silence — no error, no effect, and a doctor report that shows the
 * value they asked for. The knob layer has the same shape one level over:
 * `Knob.DEBUG` ("debug") is parsed, coerced, given a typed accessor
 * (SpliceConfig.kt:44 `public val debug: Boolean get() = bool(Knob.DEBUG)`) and read by no
 * production file at all.
 *
 * Nothing was measuring it. checks/config/quirks-keys-documented.ts proves a quirk key is
 * DOCUMENTED, which is a different question — a key can be perfectly documented and still do
 * nothing, and in fact a documented dead key is worse than an undocumented one, because the
 * documentation is a promise. This wall asks the other half: is it WIRED.
 *
 * THE DENOMINATOR COMES FROM THE SOURCE (§24), on both planes:
 *   · SCHEMA — the primary constructor of each of the five operator-facing config types, parsed
 *     on disk: Topology, DaemonConfig, HeadConfig, ProviderConfig, QuirksConfig. Each parameter
 *     yields its TOML key (@SerialName when present, else the property name). 62 keys today. The
 *     classes are LOCATED by searching the production tree for their declaration rather than by a
 *     recorded path, so moving one between files does not drop it; a class the search cannot find
 *     is a hard error, not a silently shorter list.
 *   · KNOBS — every enum constant of `Knob`, with its key string. 33 today.
 * A key added tomorrow is in scope with no edit here. Four guards refuse a vacuous pass: a
 * missing class, a parse yielding no keys on either plane, a CLASS whose attributed @SerialName
 * count disagrees with its own constructor body (a parameter the comma split dropped), and a Knob
 * enum yielding no constants, each FAIL rather than pass.
 *
 * WHAT COUNTS AS ACTED ON.
 *
 *   A SCHEMA KEY is consumed when its property is READ — not merely declared, and not merely
 *   written back:
 *     · IN ITS OWN FILE, by something other than its declaration. This is deliberate and it is
 *       the difference between a working wall and a wall that reds the correct design. The schema
 *       file is where this tree PROJECTS its TOML types into its domain types:
 *       ProviderConfig.extraWindows / windowRules / defaultContextWindow are read at
 *       Topology.kt:182-187 and handed to ModelCatalog; HeadConfig.contextWindow becomes the
 *       `window` that folds into the same catalog; QuirksConfig.compactEffort is read by a
 *       `require` in its own init that REJECTS the retired key. All four are acted on, all four
 *       are read only inside the declaring file, and a rule that demanded a foreign reader would
 *       name every one of them and be ignored within a week. `state_dir` has no read at all, in
 *       its own file or anywhere else, which is the honest distinction.
 *     · OR IN ANOTHER production main file. Bare `.prop` is enough when no other class in the
 *       tree declares a property of that name; when one does, the read must be
 *       RECEIVER-QUALIFIED. That qualification is the wall. `stateDir` is declared twice — by
 *       DaemonConfig and by StatePaths — and a name-only rule finds `statePaths.stateDir` in
 *       seven files and reports the dead key as green. The receiver spellings are derived from
 *       the schema itself (the decapitalised class name, the class name, and the names of schema
 *       properties whose declared type mentions the class, singularised for a Map/List), never
 *       hand-listed.
 *
 *   A KNOB KEY is consumed when `Knob.<CONST>` is read outside its declaration AND outside the
 *   ACCESSOR FACADE, or — one hop — when the facade accessor that reads it is itself read
 *   elsewhere. The hop is what makes this plane non-vacuous: every one of the 33 knobs is read by
 *   SpliceConfig.kt, so counting the facade as a consumer would make the whole plane green with
 *   no wiring anywhere. The hop is derived, not listed: the facade is split into its own
 *   declarations and each is asked which `Knob` constants its body names.
 *
 * TWO SURFACES ARE NOT CONSUMPTION, each with a dated reason, each checked for staleness
 * (NON_CONSUMPTION below). Both are precedented: checks/config/quirks-keys-documented.ts
 * excludes the same doctor file for the same reason, in the same words.
 *   · THE ECHO SURFACE — a doctor/report file that puts the value back out under its own key
 *     name. `put("state_dir", t.daemon.stateDir)` is the value being SHOWN, not used; counting it
 *     is how the dead key reads as live. If this file counted, this wall would find nothing.
 *   · THE ACCESSOR FACADE — SpliceConfig, the typed view over the knob map. A read there is a
 *     projection, so it is followed one hop rather than trusted.
 *
 * THE ALLOWLIST is for a key that is deliberately parsed and deliberately not acted on — a
 * retired key kept so an old config still loads, a key whose only job is to be rejected. Each
 * entry is `(key, "YYYY-MM-DD: why")`: undated fails, blank-reasoned fails (a placeholder is an
 * absence wearing a label), and an entry naming a key that IS consumed fails as stale. Empty
 * today, and that is the honest state: the two live findings are the fix row's work, not
 * exemptions.
 *
 * NOT CAUGHT, stated here rather than discovered later.
 *   · A KEY READ THROUGH A RENAME. `val d = topology.daemon` then `d.stateDir` is a read this
 *     wall cannot attribute, because `d` is not a derived receiver spelling. It reads as
 *     unconsumed — a false RED, which is the safe direction: the fix row looks, and finds the
 *     read. The unsafe direction (a false green) is what the receiver qualification and the echo
 *     surface exist to close.
 *   · A KEY WHOSE ONLY READER IS A TEST. Test sources are not scanned at all here: a key wired
 *     only into a test is not wired. That is the same judgement checks/public-surface.ts makes
 *     about test-only callers, and for the same reason.
 *   · SEMANTIC DEADNESS ONE LEVEL DOWN. A key read into a variable that is then never used, or
 *     threaded into a field nothing consults, passes. Only the first hop is checked on the schema
 *     plane and two on the knob plane; a full reachability answer needs the compiler
 *     (:fir-checks), not a regex.
 *
 * SELFTEST. `--selftest` builds temp trees and proves BOTH directions plus the boring cases:
 * GREEN on a key read in another file, on one read only by its own file's projection, on one
 * rejected by a `require` in its own init, on a knob read through its accessor, and on an
 * allowlisted key with a dated reason; RED BY NAME on a synthetic key appended to a temp copy of
 * the schema, on a key whose only reader is the echo surface (state_dir's exact shape), on a knob
 * whose accessor nobody calls (debug's exact shape), on an allowlist entry with a blank reason,
 * on a stale allowlist entry, on a missing schema class, and on a parse that yields no keys.
 *
 * Usage:
 *     bun checks/schema-keys-consumed.ts [<root>]
 *     bun checks/schema-keys-consumed.ts --report [<root>]
 *     bun checks/schema-keys-consumed.ts --selftest
 *
 * A BARE RUN IS the audit — the one gating mode, so there is no non-gating default to mis-invoke.
 * `--report` is the census and sits behind an explicit verb the gate never uses.
 */
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// parents[1]: this file lives at checks/, so the repo root is one level up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

// restructure PR 3: :client is the first module to live outside gateway/, so the production
// universe is no longer one `gateway/*` pattern. A source root this checker stops walking is a
// denominator that shrinks in silence, which is the one failure every ratchet here exists to
// prevent — so the list names every §2.2 module home, the ones that exist and the ones the next
// module commits create (a glob over an absent directory matches nothing, so the denominator can
// only grow), until PR 5 hands these checkers the build-derived source units of tools/gate.
// ONE line on purpose: the selftest proves the vacuity guard by patching this exact line.
const SRC_GLOBS = ["gateway/*/src/main", "client/src/main", "core/src/main", "upstream/src/main", "dialects/*/src/main", "providers/*/src/main", "daemon/*/src/main", "app/src/main", "quality/*/src/main"];
const SRC_GLOB = SRC_GLOBS.join(", ");

// The five operator-facing config types. Named, not path-pinned: each is LOCATED in the tree, so
// a move is a move and a disappearance is a hard error.
const SCHEMA_CLASSES = ["Topology", "DaemonConfig", "HeadConfig", "ProviderConfig", "QuirksConfig"];
const KNOB_CLASS = "Knob";

// Not consumption. (relative path, dated reason). A stale entry — the file is gone — is a hard
// error: a dead exclusion is an un-graded surface one rename later.
let NON_CONSUMPTION: [string, string][] = [
  [
    "gateway/app/src/main/kotlin/splice/app/cli/DoctorReportShape.kt",
    "2026-09-17: THE ECHO SURFACE. It puts every topology key back out under its own key name " +
      '(`put("state_dir", t.daemon.stateDir)`) — the value is being SHOWN, not used. Counting it ' +
      "would make this wall green over exactly the population it exists to name: state_dir's only " +
      "read in the whole tree is this file's line 36. Same file, same reason, same words as " +
      "checks/config/quirks-keys-documented.ts's WHAT IS NOT A DISPOSITION SURFACE.",
  ],
  [
    "core/src/main/kotlin/splice/core/config/SpliceConfig.kt",
    "2026-09-17: THE ACCESSOR FACADE over the knob map. Every one of the 33 knobs is read here, " +
      "so treating it as a consumer would make the knob plane pass with no wiring anywhere. A read " +
      "here is a PROJECTION, and it is followed one hop instead: the accessor that names the knob " +
      "must itself be read outside this file.",
  ],
];

// Keys deliberately parsed and deliberately not acted on. (key, "YYYY-MM-DD: why").
let ALLOWLIST: [string, string][] = [];
const DATED_REASON = /^\d{4}-\d{2}-\d{2}: \S/;

const classDeclTemplate = (name: string): string =>
  `^(?:public |internal |private )?(?:data |value |sealed )*class[ \\t]+${name}\\b[^\\n(]*\\(`;
const enumDeclTemplate = (name: string): string =>
  `^(?:public |internal )?enum class[ \\t]+${name}\\b[^\\n(]*\\(`;
const SERIAL_NAME = /@SerialName\(\s*"([^"]+)"\s*\)/g;
const PARAM = /\b(?:val|var)\s+(\w+)\s*:\s*([^=]+?)(?:\s*=\s*[\s\S]*)?$/;
const PROPERTY_DECL = /\b(?:val|var)\s+(\w+)\s*:/g;
const ENUM_CONSTANT = /^ {4}([A-Z][A-Z0-9_]*)\s*\(/gm;
// The key string is the enum constant's first argument, on its line or the next.
const ENUM_KEY = /^\s*"([A-Za-z0-9_.]+)"/m;
const FACADE_MEMBER = /^ {4}(?:public |private |internal )?(?:val|fun)\s+(\w+)/gm;

// ── the parser (behaviour lifted from checks/config/quirks-keys-documented.ts) ─────────

/** The primary-constructor text inside the parens at [start], comment- and string-aware.
 *
 *  KDoc is interleaved between parameters throughout these files (DaemonConfig has six such
 *  blocks), so a naive paren walk stops at the first `)` inside a default value. */
function extractConstructor(source: string, start: number): string | null {
  let i = start;
  let depth = 0;
  let bodyStart: number | null = null;
  let inString = false;
  let quote = "";
  let escape = false;
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
    if (ch === "/" && source.slice(i + 1, i + 2) === "/") {
      const nl = source.indexOf("\n", i);
      i = nl < 0 ? source.length : nl;
      continue;
    }
    if (ch === "/" && source.slice(i + 1, i + 2) === "*") {
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

const isAlnum = (ch: string | undefined): boolean => ch !== undefined && /[A-Za-z0-9]/.test(ch);

/** Split a constructor body on top-level commas, dropping comments.
 *
 *  Angle brackets are tracked SEPARATELY from brackets and only open when immediately preceded
 *  by an identifier: counting `<`/`>` as depth makes `->` and a `>` comparison decrement it, and
 *  every parameter after a lambda default falls out of the list (measured on
 *  checks/constructor-width.ts, where ast-grep's own parameter count found eight such
 *  undercounts). */
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
    if (ch === "/" && body.slice(i + 1, i + 2) === "/") {
      const nl = body.indexOf("\n", i);
      i = nl < 0 ? body.length : nl;
      continue;
    }
    if (ch === "/" && body.slice(i + 1, i + 2) === "*") {
      const end = body.indexOf("*/", i + 2);
      i = end < 0 ? body.length : end + 2;
      continue;
    }
    if ("({[".includes(ch)) depth += 1;
    else if (")}]".includes(ch)) depth -= 1;
    else if (
      ch === "<" &&
      i > 0 &&
      (isAlnum(body[i - 1]) || body[i - 1] === "_" || body[i - 1] === ">") &&
      body.slice(i + 1, i + 2) !== "="
    ) {
      angle += 1;
    } else if (ch === ">" && angle > 0 && body.slice(i - 1, i) !== "-" && body.slice(i + 1, i + 2) !== "=") {
      angle -= 1;
    } else if (ch === "," && depth === 0 && angle === 0) {
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

/** // and block comments out, string-aware. Only the @SerialName count guard reads this. */
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
    if (ch === "/" && source.slice(i + 1, i + 2) === "/") {
      const nl = source.indexOf("\n", i);
      i = nl < 0 ? source.length : nl;
      continue;
    }
    if (ch === "/" && source.slice(i + 1, i + 2) === "*") {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? source.length : end + 2;
      continue;
    }
    out.push(ch);
    i += 1;
  }
  return out.join("");
}

// ── the denominator ───────────────────────────────────────────────────────────────────

class Key {
  readonly plane: string;
  readonly owner: string;
  readonly prop: string;
  readonly key: string;
  readonly declaredIn: string;
  readonly line: number;

  constructor(plane: string, owner: string, prop: string, key: string, declaredIn: string, line: number) {
    this.plane = plane; // "schema" | "knob"
    this.owner = owner; // class or enum name
    this.prop = prop; // property name / enum constant
    this.key = key; // the TOML / knob key an operator writes
    this.declaredIn = declaredIn;
    this.line = line;
  }

  locus(): string {
    return `${this.declaredIn}:${this.line}`;
  }
}

/** Python's repr() of a string. */
const pyRepr = (value: string): string => `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;

/** Python's repr() of a list of strings. */
const pyReprList = (items: string[]): string => `[${items.map(pyRepr).join(", ")}]`;

function sources(root: string): Map<string, string> {
  const out = new Map<string, string>();
  const files = SRC_GLOBS
    .flatMap((p) => [...new Bun.Glob(`${p}/**/*.kt`).scanSync({ cwd: root, followSymlinks: true })])
    .sort();
  for (const rel of files) out.set(rel, readFileSync(join(root, rel), "utf8"));
  return out;
}

function locate(files: Map<string, string>, pattern: string): { rel: string; match: RegExpExecArray } | null {
  const compiled = new RegExp(pattern, "m");
  for (const rel of [...files.keys()].sort()) {
    const match = compiled.exec(files.get(rel) as string);
    if (match !== null) return { rel, match };
  }
  return null;
}

/** (keys, class -> [(prop, key, declared type)], problems). */
function schemaKeys(files: Map<string, string>): {
  keys: Key[];
  shapes: Map<string, [string, string, string][]>;
  problems: string[];
} {
  const keys: Key[] = [];
  const shapes = new Map<string, [string, string, string][]>();
  const problems: string[] = [];
  for (const name of SCHEMA_CLASSES) {
    const found = locate(files, classDeclTemplate(name));
    if (found === null) {
      problems.push(
        `${name}: no \`class ${name}(\` found under ${SRC_GLOB} — it is part of the denominator, ` +
          `so its absence cannot pass. If it was renamed, rename it in SCHEMA_CLASSES too.`,
      );
      continue;
    }
    const text = files.get(found.rel) as string;
    const body = extractConstructor(text, found.match.index + found.match[0].length - 1);
    if (body === null) {
      problems.push(`${found.rel}: ${name}'s primary constructor could not be walked`);
      continue;
    }
    const shape: [string, string, string][] = [];
    let attributed = 0;
    for (const raw of splitParams(body)) {
      const param = PARAM.exec(raw);
      if (param === null) continue;
      const prop = param[1];
      const serial = SERIAL_NAME.exec(raw);
      SERIAL_NAME.lastIndex = 0;
      const key = serial !== null ? serial[1] : prop;
      if (serial !== null) attributed += 1;
      const line = text.slice(0, found.match.index).split("\n").length;
      keys.push(new Key("schema", name, prop, key, found.rel, line));
      shape.push([prop, key, param[2].trim()]);
    }
    shapes.set(name, shape);
    // PARSER-DRIFT GUARD, per CLASS rather than per file (the idiom of
    // quirks-keys-documented.ts, corrected for a file that holds more than one type). The
    // @SerialName annotations this run ATTRIBUTED to parameters must equal the annotations
    // inside the constructor BODY: a parameter the comma split loses takes its key with it,
    // and a shorter key list is the one failure mode that would otherwise read as green.
    // Counted per class because Topology.kt and QuirksConfig.kt each declare several types
    // (ModelEntry, ExtraWindow, WindowRule, ClaudeWrapperConfig, ToolSurfaceConfig), whose
    // annotations are not in these five constructors at all — a whole-file count reads 46
    // against 41 on this tree and would red every run for the wrong reason.
    const inBody = [...stripComments(body).matchAll(SERIAL_NAME)].length;
    if (attributed !== inBody) {
      problems.push(
        `${found.rel}: ${name} — attributed ${attributed} @SerialName key(s) but its constructor ` +
          `holds ${inBody}; the parser dropped a parameter, so no key list from this run can ` +
          `be trusted`,
      );
    }
  }
  return { keys, shapes, problems };
}

/** (keys, the file declaring Knob, problems). */
function knobKeys(files: Map<string, string>): { keys: Key[]; knobFile: string; problems: string[] } {
  const found = locate(files, enumDeclTemplate(KNOB_CLASS));
  if (found === null) {
    return {
      keys: [],
      knobFile: "",
      problems: [
        `no \`enum class ${KNOB_CLASS}(\` found under ${SRC_GLOB} — the knob plane's denominator is ` +
          "absent, which cannot pass",
      ],
    };
  }
  const rel = found.rel;
  const text = files.get(rel) as string;
  const body = text.slice(found.match.index + found.match[0].length);
  const keys: Key[] = [];
  const constants = [...body.matchAll(ENUM_CONSTANT)];
  for (let index = 0; index < constants.length; index += 1) {
    const constant = constants[index];
    const start = constant.index as number;
    const end = index + 1 < constants.length ? (constants[index + 1].index as number) : body.length;
    const keyMatch = ENUM_KEY.exec(body.slice(start + constant[0].length, end));
    if (keyMatch === null) {
      // A constant whose key string cannot be read is a parse failure, not a pass.
      return {
        keys,
        knobFile: rel,
        problems: [
          `${rel}: ${constant[1]} declares no readable key string — the knob denominator ` +
            "cannot be trusted",
        ],
      };
    }
    const line =
      text.slice(0, found.match.index + found.match[0].length + start).split("\n").length;
    keys.push(new Key("knob", KNOB_CLASS, constant[1], keyMatch[1], rel, line));
  }
  if (keys.length === 0) {
    return { keys: [], knobFile: rel, problems: [`${rel}: parsed 0 ${KNOB_CLASS} constants — refusing to pass vacuously`] };
  }
  return { keys, knobFile: rel, problems: [] };
}

// ── consumption ───────────────────────────────────────────────────────────────────────

/** Every spelling a receiver of [name] can have, DERIVED from the schema shapes.
 *
 *  The decapitalised class name, the class name itself, and the name of every schema property
 *  whose declared type mentions the class — plus the singular of a plural one, because
 *  `providers: Map<String, ProviderConfig>` is read as `provider.baseUrl` at the element. */
function receiversFor(name: string, shapes: Map<string, [string, string, string][]>): Set<string> {
  const out = new Set<string>([name, name[0].toLowerCase() + name.slice(1)]);
  for (const properties of shapes.values()) {
    for (const [prop, , declared] of properties) {
      if (new RegExp(`\\b${escapeRe(name)}\\b`).test(declared)) {
        out.add(prop);
        if (prop.endsWith("s")) out.add(prop.slice(0, -1));
      }
    }
  }
  return out;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function consumers(files: Map<string, string>, exclude: Set<string>): Map<string, string> {
  const out = new Map<string, string>();
  for (const [rel, text] of files) if (!exclude.has(rel)) out.set(rel, text);
  return out;
}

/** (unconsumed keys with the reason each is red, keys examined, problems). */
function unconsumed(root: string): { red: [Key, string][]; examined: number; problems: string[] } {
  const files = sources(root);
  const problems: string[] = [];
  const excluded = new Set(NON_CONSUMPTION.map(([rel]) => rel));
  for (const [rel, reason] of NON_CONSUMPTION) {
    if (!DATED_REASON.test(reason.trim())) {
      problems.push(
        `NON_CONSUMPTION entry for ${rel} has no dated reason — every exclusion starts ` +
          "'YYYY-MM-DD: <why>'. An exclusion nobody can evaluate is indistinguishable from " +
          "one nobody should have granted.",
      );
    }
    if (!files.has(rel)) {
      problems.push(
        `NON_CONSUMPTION names ${rel}, which is not a production main file any more — delete ` +
          "the entry. A stale exclusion is an un-graded surface one rename later.",
      );
    }
  }
  for (const [key, reason] of ALLOWLIST) {
    if (!DATED_REASON.test(reason.trim())) {
      problems.push(
        `ALLOWLIST entry ${pyRepr(key)} has no dated reason — a blank or undated reason is an ` +
          "absence wearing a label, not a disposition.",
      );
    }
  }

  const { keys: schema, shapes, problems: schemaProblems } = schemaKeys(files);
  const { keys: knobs, knobFile, problems: knobProblems } = knobKeys(files);
  problems.push(...schemaProblems, ...knobProblems);
  if (schema.length === 0 && knobs.length === 0) {
    problems.push(
      `parsed 0 config keys under ${SRC_GLOB} — refusing to pass vacuously, because a green ` +
        "over an empty denominator is what this wall exists to prevent",
    );
    return { red: [], examined: 0, problems };
  }

  // Which files declare a property of each name: the AMBIGUITY index, from the source. A name
  // only one class owns can be matched bare; a name two classes own must be receiver-qualified,
  // which is the whole reason `stateDir` does not read as consumed through `statePaths.stateDir`.
  const declaredBy = new Map<string, Set<string>>();
  for (const [rel, text] of files) {
    for (const m of text.matchAll(PROPERTY_DECL)) {
      const set = declaredBy.get(m[1]);
      if (set === undefined) declaredBy.set(m[1], new Set([rel]));
      else set.add(rel);
    }
  }

  const facade =
    NON_CONSUMPTION.map(([rel]) => rel).find((rel) => rel.endsWith("SpliceConfig.kt") && files.has(rel)) ?? "";
  const facadeAccessors = knobAccessors(files.get(facade) ?? "");

  const allowlisted = new Set(ALLOWLIST.map(([key]) => key));
  const red: [Key, string][] = [];
  const consumedKeys = new Set<string>();
  for (const key of schema) {
    const why = schemaUnconsumed(key, shapes, files, declaredBy, excluded);
    if (why === null) consumedKeys.add(`${key.owner}.${key.prop}`);
    else if (!allowlisted.has(key.key)) red.push([key, why]);
  }
  for (const key of knobs) {
    const why = knobUnconsumed(key, files, knobFile, facade, facadeAccessors, excluded);
    if (why === null) consumedKeys.add(`${key.owner}.${key.prop}`);
    else if (!allowlisted.has(key.key)) red.push([key, why]);
  }

  for (const [key] of ALLOWLIST) {
    const live = [...schema, ...knobs].filter((k) => k.key === key);
    if (live.length === 0) {
      problems.push(
        `ALLOWLIST names ${pyRepr(key)}, which is not a config key any more — drop the entry; it ` +
          "currently exempts nothing.",
      );
    } else if (live.some((k) => consumedKeys.has(`${k.owner}.${k.prop}`))) {
      problems.push(
        `ALLOWLIST names ${pyRepr(key)}, which IS acted on now — drop the entry, so the list keeps ` +
          "meaning 'deliberately inert'.",
      );
    }
  }
  return { red, examined: schema.length + knobs.length, problems };
}

/** None when the key is acted on; otherwise the sentence saying how it is dead. */
function schemaUnconsumed(
  key: Key,
  shapes: Map<string, [string, string, string][]>,
  files: Map<string, string>,
  declaredBy: Map<string, Set<string>>,
  excluded: Set<string>,
): string | null {
  const own = files.get(key.declaredIn) as string;
  // (A) IN ITS OWN FILE, by something other than its own declaration. The declaration text is
  // removed first, and a bare `prop =` (a named-argument WRITE) is not a read: putting the value
  // back into a constructor call is not acting on it.
  const stripped = removeDeclaration(own, key.prop);
  const read = new RegExp(`(?<![\\w$])${escapeRe(key.prop)}\\b(?!\\s*=(?!=))`);
  if (read.test(stripped)) return null;
  // (B) IN ANOTHER production main file, receiver-qualified when the name is ambiguous.
  const others = declaredBy.get(key.prop) ?? new Set<string>();
  const ambiguous = [...others].some((rel) => rel !== key.declaredIn);
  let pattern: RegExp;
  if (ambiguous) {
    const spellings = [...receiversFor(key.owner, shapes)].sort((a, b) => b.length - a.length);
    pattern = new RegExp(
      `(?:${spellings.map(escapeRe).join("|")})\\s*\\??\\s*\\.\\s*${escapeRe(key.prop)}\\b`,
    );
  } else {
    pattern = new RegExp(`(?<![\\w$])${escapeRe(key.prop)}\\b`);
  }
  for (const [rel, text] of files) {
    if (rel === key.declaredIn || excluded.has(rel)) continue;
    if (pattern.test(text)) return null;
  }
  const echo = [...excluded].sort().filter((rel) => files.has(rel) && pattern.test(files.get(rel) as string));
  const tail =
    echo.length > 0
      ? ` Its only read in the tree is the echo surface (${echo.join(", ")}), which shows the value ` +
        `rather than using it.`
      : " Nothing reads it anywhere, in its own file or outside it.";
  return (
    `${key.owner}.${key.prop} (TOML key \`${key.key}\`) is PARSED AND NEVER ACTED ON.` +
    tail +
    " Thread it into the behaviour it promises, or retire it with a dated ALLOWLIST entry in " +
    "checks/schema-keys-consumed.ts saying why it is deliberately inert."
  );
}

/** The file with [prop]'s own `val prop:` / `var prop:` declaration lines blanked.
 *
 *  Line-based on purpose: the declaration is what must not count as its own read, and blanking
 *  the line keeps every other occurrence at its original offset. */
function removeDeclaration(text: string, prop: string): string {
  const decl = new RegExp(`\\b(?:val|var)\\s+${escapeRe(prop)}\\s*:`);
  return text
    .split("\n")
    .map((line) => (decl.test(line) ? "" : line))
    .join("\n");
}

/** Knob constant -> the facade accessor names whose bodies read it.
 *
 *  Split per DECLARATION rather than by a multi-line regex, so a knob is attributed to the
 *  accessor that actually names it (a sloppier scan pairs `FOLD_MARKER_TEXT` with
 *  `foldMaxContinue` and the hop then follows the wrong name). */
function knobAccessors(facade: string): Map<string, Set<string>> {
  const marks = [...facade.matchAll(FACADE_MEMBER)].map((m) => [m.index as number, m[1]] as [number, string]);
  const out = new Map<string, Set<string>>();
  for (let index = 0; index < marks.length; index += 1) {
    const [start, name] = marks[index];
    const end = index + 1 < marks.length ? marks[index + 1][0] : facade.length;
    for (const m of facade.slice(start, end).matchAll(/Knob\.([A-Z][A-Z0-9_]*)/g)) {
      const set = out.get(m[1]);
      if (set === undefined) out.set(m[1], new Set([name]));
      else set.add(name);
    }
  }
  return out;
}

function knobUnconsumed(
  key: Key,
  files: Map<string, string>,
  knobFile: string,
  facade: string,
  accessors: Map<string, Set<string>>,
  excluded: Set<string>,
): string | null {
  const direct = new RegExp(`Knob\\.${escapeRe(key.prop)}\\b`);
  for (const [rel, text] of files) {
    if (rel === knobFile || rel === facade) continue;
    if (excluded.has(rel)) continue;
    if (direct.test(text)) return null;
  }
  const names = accessors.get(key.prop) ?? new Set<string>();
  for (const name of [...names].sort()) {
    const hop = new RegExp(`\\.${escapeRe(name)}\\b`);
    for (const [rel, text] of files) {
      if (rel === knobFile || rel === facade || excluded.has(rel)) continue;
      if (hop.test(text)) return null;
    }
  }
  if (names.size === 0) {
    return (
      `Knob.${key.prop} (key \`${key.key}\`) is PARSED AND NEVER ACTED ON: no production file reads ` +
      `it and the accessor facade does not expose it either. Wire it, or retire it with a dated ` +
      `ALLOWLIST entry.`
    );
  }
  return (
    `Knob.${key.prop} (key \`${key.key}\`) is PARSED AND NEVER ACTED ON: its only reader is the ` +
    `accessor facade (${[...names].sort().join(", ")}), and nothing reads that accessor either. An ` +
    `operator who sets \`${key.key}\` gets silence. Wire it, or retire it with a dated ALLOWLIST ` +
    `entry saying why it is deliberately inert.`
  );
}

// ── modes ─────────────────────────────────────────────────────────────────────────────

function audit(root: string): { code: number; out: string; err: string } {
  const { red, examined, problems } = unconsumed(root);
  const out: string[] = [`schema-keys-consumed: ${examined} config key(s) examined (${SRC_GLOB})`];
  if (problems.length === 0 && red.length === 0) {
    out.push(
      "GREEN: every key on Topology / DaemonConfig / HeadConfig / ProviderConfig / " +
        "QuirksConfig and every Knob key is read by something that acts on it",
    );
    return { code: 0, out: out.join("\n") + "\n", err: "" };
  }
  const err: string[] = [`\nFAIL: schema-keys-consumed — ${red.length + problems.length} problem(s):`];
  for (const problem of problems) err.push("  x " + problem);
  for (const [key, reason] of red) err.push(`  x ${key.locus()}: ${reason}`);
  return { code: 1, out: out.join("\n") + "\n", err: err.join("\n") + "\n" };
}

function report(root: string): number {
  const files = sources(root);
  const { keys: schema, problems: schemaProblems } = schemaKeys(files);
  const { keys: knobs, problems: knobProblems } = knobKeys(files);
  const { red, examined, problems } = unconsumed(root);
  const dead = new Set(red.map(([key]) => `${key.owner}.${key.prop}`));
  for (const problem of [...schemaProblems, ...knobProblems, ...problems]) {
    process.stdout.write("  UNTRUSTED: " + problem + "\n");
  }
  process.stdout.write(`schema-keys-consumed: ${examined} key(s), ${red.length} parsed-and-never-acted-on\n`);
  for (const key of [...schema, ...knobs]) {
    const state = dead.has(`${key.owner}.${key.prop}`) ? "DEAD" : "acted on";
    // The Python source is `{key.owner}.{key.prop:24s}` — and an f-string format spec binds to the
    // LAST expression before the colon, so only `prop` is padded and `owner` is not. Padding the
    // concatenation instead shifts every column by the length of the owner name, which is what the
    // first draft of this port did. Only `report` prints this line, which is why the other three
    // modes were byte-identical while the census was wrong.
    process.stdout.write(
      `  ${key.plane.padEnd(6)} ${key.owner}.${key.prop.padEnd(24)} key ` +
        `${key.key.padEnd(26)} ${state.padEnd(9)} ${key.locus()}\n`,
    );
  }
  return red.length > 0 || problems.length > 0 ? 1 : 0;
}

// ── selftest ──────────────────────────────────────────────────────────────────────────

const MODULE = "gateway/zz-selftest/src/main/kotlin/splice/selftest";

const SCHEMA_FIXTURE = `package splice.selftest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class Topology(
    val daemon: DaemonConfig = DaemonConfig(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val heads: Map<String, HeadConfig> = emptyMap(),
)

@Serializable
public data class DaemonConfig(
    @SerialName("control_port") val controlPort: Int? = null,
    @SerialName("state_dir") val stateDir: String? = null,
    @SerialName("echoed_only") val echoedOnly: String? = null,
)

@Serializable
public data class HeadConfig(
    val port: Int,
    /** A KDoc between parameters, with a comma and a ) in it. */
    @SerialName("context_window") val contextWindow: Long? = null,
)

@Serializable
public data class ProviderConfig(
    @SerialName("base_url") val baseUrl: String,
    val quirks: QuirksConfig = QuirksConfig(),
    @SerialName("extra_windows") val extraWindows: List<String> = emptyList(),
) {
    public fun toCatalog(): Catalog = Catalog(extraWindows = extraWindows)
}

@Serializable
public data class QuirksConfig(
    val store: Boolean = false,
    @SerialName("compact_effort") val compactEffort: String? = null,
) {
    init {
        require(compactEffort == null) { "compact_effort is retired" }
    }
}
`;

const CATALOG_FIXTURE = `package splice.selftest

public class Catalog(val extraWindows: List<String> = emptyList()) {
    public fun widest(): String? = extraWindows.maxOrNull()
}
`;

// The consumer: reads controlPort, port and contextWindow for real. Nothing here reads stateDir
// or echoedOnly.
const CONSUMER_FIXTURE = `package splice.selftest

internal class Wiring(private val topology: Topology) {
    fun bind(): Int = topology.daemon.controlPort ?: 0
    fun providerKeys(): Set<String> = topology.providers.keys
    fun headKeys(): Set<String> = topology.heads.keys
    fun window(head: HeadConfig): Long = head.contextWindow ?: 0
    fun port(head: HeadConfig): Int = head.port
    fun base(provider: ProviderConfig): String = provider.baseUrl
    fun store(quirks: QuirksConfig): Boolean = quirks.store
}
`;

// The echo surface: puts state_dir and echoed_only back out under their own key names. Also
// declares its own `stateDir`, which is what makes the receiver qualification load-bearing.
const ECHO_FIXTURE = `package splice.selftest

internal class DoctorReportShape(private val paths: StatePaths) {
    fun shape(t: Topology): Map<String, Any?> = mapOf(
        "state_dir" to t.daemon.stateDir,
        "echoed_only" to t.daemon.echoedOnly,
        "local_state_dir" to paths.stateDir,
    )
}

internal class StatePaths {
    val stateDir: String = "/var/lib/splice"
}
`;

const KNOB_FIXTURE = `package splice.selftest

public enum class Knob(
    public val key: String,
    public val default: Any?,
) {
    PORT("port", 3099L),
    WIRED_DIRECT("wiredDirect", "x"),
    WIRED_VIA_ACCESSOR("wiredViaAccessor", true),
    DEBUG("debug", false),
}
`;

const FACADE_FIXTURE = `package splice.selftest

public class SpliceConfig internal constructor(private val m: Map<String, Any?>) {
    public val port: Int get() = (m[Knob.PORT.key] as? Int) ?: 0
    public val wiredViaAccessor: Boolean get() = m[Knob.WIRED_VIA_ACCESSOR.key] == true
    public val debug: Boolean get() = m[Knob.DEBUG.key] == true
}
`;

const KNOB_CONSUMER_FIXTURE = `package splice.selftest

internal class KnobWiring(private val cfg: SpliceConfig) {
    fun direct(): String = Knob.WIRED_DIRECT.key
    fun viaAccessor(): Boolean = cfg.wiredViaAccessor
    fun bind(): Int = cfg.port
}
`;

const FIXTURE_NON_CONSUMPTION: [string, string][] = [
  [`${MODULE}/DoctorReportShape.kt`, "2026-09-17: the echo surface fixture"],
  [`${MODULE}/SpliceConfig.kt`, "2026-09-17: the accessor facade fixture"],
];

function fixture(root: string, overrides: Record<string, string> = {}): void {
  const written: Record<string, string> = {
    "Schema.kt": SCHEMA_FIXTURE,
    "Catalog.kt": CATALOG_FIXTURE,
    "Wiring.kt": CONSUMER_FIXTURE,
    "DoctorReportShape.kt": ECHO_FIXTURE,
    "Knob.kt": KNOB_FIXTURE,
    "SpliceConfig.kt": FACADE_FIXTURE,
    "KnobWiring.kt": KNOB_CONSUMER_FIXTURE,
    ...overrides,
  };
  for (const [name, text] of Object.entries(written)) {
    if (!text) continue;
    const path = join(root, MODULE, name);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text, "utf8");
  }
}

function selftest(): number {
  const failures: string[] = [];
  const realNonConsumption = NON_CONSUMPTION;
  const realAllowlist = ALLOWLIST;
  NON_CONSUMPTION = FIXTURE_NON_CONSUMPTION;

  const run = (root: string): { code: number; text: string; red: [Key, string][] } => {
    const result = audit(root);
    const { red } = unconsumed(root);
    return { code: result.code, text: result.out + result.err, red };
  };

  const arm = (label: string, build: (root: string) => void, expect: string | null): void => {
    const root = join(tmpdir(), `schema-keys-${process.pid}-${Math.random().toString(36).slice(2)}`);
    mkdirSync(root, { recursive: true });
    try {
      build(root);
      const { code, text } = run(root);
      if (expect === null) {
        if (code !== 0) {
          failures.push(`${label} — must be GREEN, got exit ${code}: ${text.trim().slice(0, 400)}`);
        }
      } else if (code === 0) {
        failures.push(`${label} — MUST be RED, exited 0`);
      } else if (!text.includes(expect)) {
        failures.push(`${label} — red for the wrong reason (want ${pyRepr(expect)}): ${text.trim().slice(0, 400)}`);
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  };

  try {
    // 1. THE LIVE FINDINGS' SHAPES, in one fixture: state_dir (read only by the echo surface,
    //    with a same-named property on another class) and debug (read only by an accessor
    //    nobody calls) must BOTH be red by name, and nothing else may be.
    const base = (root: string): void => fixture(root);
    arm("1a. a key read only by the ECHO surface is dead (state_dir's exact shape)", base, "state_dir");
    arm("1b. and the reason names the echo surface rather than claiming nothing reads it", base, "echo surface");
    arm("1c. a knob read only by an accessor nobody calls is dead (debug's exact shape)", base, "`debug`");

    {
      const root = join(tmpdir(), `schema-keys-${process.pid}-${Math.random().toString(36).slice(2)}`);
      mkdirSync(root, { recursive: true });
      try {
        fixture(root);
        const { red } = run(root);
        const dead = red.map(([key]) => key.key).sort();
        if (dead.join(",") !== "debug,echoed_only,state_dir") {
          failures.push(
            `1d. the fixture's dead set must be exactly state_dir, echoed_only and debug ` +
              `(every other key is wired, including the two read only inside their own file): got ` +
              `${pyReprList(dead)}`,
          );
        }
      } finally {
        rmSync(root, { recursive: true, force: true });
      }
    }

    // 2. the KEYS THAT MUST NOT BE RED, each a real pattern from this tree:
    //    contextWindow — ambiguous name, read receiver-qualified in another file;
    //    extraWindows  — read ONLY inside its own declaring file, projected into a domain type;
    //    compactEffort — read ONLY by a `require` in its own init, which is acting on it;
    //    port/store/baseUrl/controlPort — ordinary cross-file reads;
    //    wiredDirect   — a knob read directly;
    //    wiredViaAccessor / port — a knob read through its accessor (the one hop).
    //    Arm 1d above pins all of them at once, so this arm asserts the GREEN half explicitly.
    const wiredOnly = (root: string): void => {
      fixture(root, {
        "Schema.kt": SCHEMA_FIXTURE.replace(
          '    @SerialName("state_dir") val stateDir: String? = null,\n',
          "",
        ).replace('    @SerialName("echoed_only") val echoedOnly: String? = null,\n', ""),
        "DoctorReportShape.kt": ECHO_FIXTURE.replace('        "state_dir" to t.daemon.stateDir,\n', "").replace(
          '        "echoed_only" to t.daemon.echoedOnly,\n',
          "",
        ),
        "Knob.kt": KNOB_FIXTURE.replace('    DEBUG("debug", false),\n', ""),
        "SpliceConfig.kt": FACADE_FIXTURE.replace(
          "    public val debug: Boolean get() = m[Knob.DEBUG.key] == true\n",
          "",
        ),
      });
    };
    arm(
      "2. the fully-wired twin is GREEN (own-file projection, own-init require, one-hop accessor)",
      wiredOnly,
      null,
    );

    // 3. THE MUTATION THIS ROW REQUIRES: a synthetic key appended to a temp copy of the schema.
    const synthetic = (root: string): void => {
      fixture(root, {
        "Schema.kt": SCHEMA_FIXTURE.replace(
          "    val store: Boolean = false,",
          '    val store: Boolean = false,\n    @SerialName("fake_new_key") val fakeNewKey: Boolean? = null,',
        ),
      });
    };
    arm("3. a synthetic key nothing reads is RED BY NAME", synthetic, "fake_new_key");

    // 4. the allowlist: a dated reason is a disposition, a blank one is not, a stale one fails.
    const allowlisted = (root: string): void => {
      ALLOWLIST = [
        ["state_dir", "2026-09-17: fixture — deliberately inert"],
        ["echoed_only", "2026-09-17: fixture — deliberately inert"],
        ["debug", "2026-09-17: fixture — deliberately inert"],
      ];
      fixture(root);
    };
    arm("4a. a dated, reasoned ALLOWLIST entry is a disposition", allowlisted, null);
    ALLOWLIST = [];

    const blankReason = (root: string): void => {
      ALLOWLIST = [
        ["state_dir", "  "],
        ["echoed_only", "2026-09-17: fixture"],
        ["debug", "2026-09-17: fixture"],
      ];
      fixture(root);
    };
    arm("4b. an ALLOWLIST entry with a blank reason is a hard error", blankReason, "absence wearing a label");
    ALLOWLIST = [];

    const staleEntry = (root: string): void => {
      ALLOWLIST = [
        ["state_dir", "2026-09-17: fixture"],
        ["echoed_only", "2026-09-17: fixture"],
        ["debug", "2026-09-17: fixture"],
        ["port", "2026-09-17: fixture — but port IS wired"],
      ];
      fixture(root);
    };
    arm("4c. an ALLOWLIST entry naming a key that IS acted on fails as stale", staleEntry, "IS acted on");
    ALLOWLIST = [];

    // 5. a stale NON_CONSUMPTION entry is a hard error — a dead exclusion is an un-graded surface.
    const staleExclusion = (root: string): void => {
      NON_CONSUMPTION = [
        ...FIXTURE_NON_CONSUMPTION,
        [`${MODULE}/Gone.kt`, "2026-09-17: fixture — names nothing"],
      ];
      fixture(root);
    };
    arm("5. a NON_CONSUMPTION entry naming a file that is gone is a hard error", staleExclusion, "stale exclusion");
    NON_CONSUMPTION = FIXTURE_NON_CONSUMPTION;

    // 6. a missing schema class is a hard error, not a shorter list.
    const missingClass = (root: string): void => {
      fixture(root, {
        "Schema.kt": SCHEMA_FIXTURE.replace("class QuirksConfig(", "class QuirksRenamed("),
      });
    };
    arm("6. a schema class the tree no longer declares is a hard error", missingClass, "part of the denominator");

    // 7. THE BORING CASES (§24), which are the ones that get waved through.
    const empty = (root: string): void => {
      mkdirSync(join(root, MODULE), { recursive: true });
    };
    arm("7a. a tree with no config keys at all must REFUSE, not pass vacuously", empty, "vacuously");

    const oneKey = (root: string): void => {
      fixture(root, {
        "Schema.kt":
          "package splice.selftest\n\n" +
          "public data class Topology(val daemon: DaemonConfig = DaemonConfig())\n" +
          "public data class DaemonConfig(val only: Int = 0)\n" +
          "public data class HeadConfig(val port: Int)\n" +
          "public data class ProviderConfig(val baseUrl: String)\n" +
          "public data class QuirksConfig(val store: Boolean = false)\n",
        "Catalog.kt": "",
        "Wiring.kt":
          "package splice.selftest\n\n" +
          "internal class Wiring(private val t: Topology) {\n" +
          "    fun a(): Int = t.daemon.only\n" +
          "    fun b(h: HeadConfig): Int = h.port\n" +
          "    fun c(p: ProviderConfig): String = p.baseUrl\n" +
          "    fun d(q: QuirksConfig): Boolean = q.store\n" +
          "}\n",
        "DoctorReportShape.kt": "package splice.selftest\n\ninternal class DoctorReportShape\n",
        "Knob.kt":
          "package splice.selftest\n\n" +
          "public enum class Knob(public val key: String) {\n" +
          '    ONLY("only"),\n' +
          "}\n",
        "SpliceConfig.kt":
          "package splice.selftest\n\n" +
          "public class SpliceConfig {\n" +
          "    public val only: String get() = Knob.ONLY.key\n" +
          "}\n",
        "KnobWiring.kt":
          "package splice.selftest\n\n" +
          "internal class KnobWiring(private val c: SpliceConfig) { fun a() = c.only }\n",
      });
    };
    arm("7b. the one-key-per-plane tree grades green WITH its count", oneKey, null);
  } finally {
    NON_CONSUMPTION = realNonConsumption;
    ALLOWLIST = realAllowlist;
  }

  if (failures.length > 0) {
    process.stdout.write("schema-keys-consumed SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  x " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "schema-keys-consumed SELFTEST OK — the fully-wired twin is green (a key projected inside " +
      "its own file, a key rejected by its own init, an ambiguous name read receiver-qualified, a " +
      "knob read directly and a knob read through one accessor hop), and a dated reasoned " +
      "allowlist entry is a disposition; a key read only by the echo surface, a knob read only by " +
      "an uncalled accessor, a synthetic key, a blank-reasoned allowlist entry, a stale allowlist " +
      "entry, a stale non-consumption entry, a renamed schema class and an empty denominator are " +
      "all red by name\n",
  );
  return 0;
}

const USAGE = `usage: schema-keys-consumed [<root>] [--report] [--selftest]
  <root>      tree to measure (default: the repo root)
  --report    every key with its disposition, no gating
  --selftest  red-green proof, out of tree
`;

function main(argv: string[]): number {
  let root: string | null = null;
  let selfTest = false;
  let reportMode = false;
  for (const arg of argv) {
    if (arg === "--selftest") selfTest = true;
    else if (arg === "--report") reportMode = true;
    else if (arg.startsWith("-")) {
      process.stderr.write(`schema-keys-consumed: unrecognised argument '${arg}'\n${USAGE}`);
      return 2;
    } else if (root === null) root = arg;
  }
  if (selfTest) return selftest();
  const resolved = root === null ? ROOT : realpathSync(root);
  if (!existsSync(resolved)) {
    process.stderr.write(`schema-keys-consumed: ${resolved} does not exist\n`);
    return 2;
  }
  return reportMode ? report(resolved) : (() => {
    const { code, out, err } = audit(resolved);
    if (out) process.stdout.write(out);
    if (err) process.stderr.write(err);
    return code;
  })();
}

process.exit(main(process.argv.slice(2)));
