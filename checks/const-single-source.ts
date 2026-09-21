#!/usr/bin/env bun
/**
 * V4-88 — a constant that has ONE MEANING has one declaration site, and no comment is
 * load-bearing for equality.
 *
 * WHY THIS EXISTS (ARCH-AUDIT 2026-09-17, audit B rows 4/9/11 and C rows 7/11). Kotlin main sources
 * in this tree carry no `companion` blocks (kt-no-companion-objects), so every constant is a
 * file-scope `const val`. That style is right, and it has one failure mode: a value needed in a
 * second file gets a second `const val` instead of an import, and nothing afterwards keeps the two
 * equal. The audit found the mature form of that drift — HeadAdmission.kt's MAX_CLIENT_HOLD_MS and
 * RateLimitCooldown.kt's MAX_RATE_LIMIT_COOLDOWN_MS are 120_000L each, and the only thing holding
 * them equal is a PROSE COMMENT that says "The two must stay equal". A comment is not a wall. When
 * one of those two numbers moves, the gateway tells the client to come back at a time the cooldown
 * has not finished, and every test stays green.
 *
 * WHY THE FIRST VERSION OF THIS WALL WAS WRONG, recorded because the correction is the design
 * (orchestrator review 2026-09-17, V4-88 REDO). It reported all 144 duplicated names as errors
 * "by name". Sampling those findings killed the premise: FIELD_CONTENT = "content" in five dialect
 * files, the CliStyle / MultiSelectPrompt escape codes, FIELD_ID = "id" in codemode versus
 * responses — these are FILE-LOCAL names for DIFFERENT wires whose values coincide. Making one
 * import the other would ADD cross-module coupling, which is the opposite of what the audit is for.
 * COLLISION was the same story (CONNECT_TIMEOUT_MS in unrelated clients, per-provider CLIENT_IDs).
 * Deriving the denominator from the source (§24) is necessary and not sufficient: the predicate over
 * it still has to be the right one, or a wall with 144 correct-looking errors gets suppressed and
 * the 5 real ones go with it. So the classes are now graded by how certain the drift is:
 *
 *   STRICT — red always, no baseline, no allowlist, no escape. These are the shapes where the code
 *            itself says the values must agree:
 *     EQUAL-BY-COMMENT  a const whose adjacent comment ASSERTS an equality obligation ("must stay
 *                       equal", "must match", "keep in sync", "mirroring X") AND names another
 *                       const. The obligation is real and the enforcement is prose — the scar above.
 *     KNOB-SHADOW       a const whose value equals a Knob's declared default and whose name is
 *                       within one qualifier token of that Knob's. The Knob IS the operator-facing
 *                       single source; a local literal default forks it, so `splice config` and the
 *                       code disagree the moment the Knob moves.
 *     NAMED-SCAR        a COPY/COLLISION name on the list below, each carrying a written reason why
 *                       it is ONE meaning. Red regardless of the baseline. This list is the fix
 *                       row's checklist, and it is the one hand-authored thing in this file — so it
 *                       is small, reasoned per entry, and cross-checked: an entry naming something
 *                       that is no longer duplicated fails as STALE, exactly like a baseline entry.
 *
 *   RATCHET — recorded, then held. Certainty here is low per finding and high in aggregate: most of
 *            the 144 are fine, and the tree should not grow more of them while nobody is looking.
 *     COPY       the same const NAME in 2+ files with the same NORMALISED value.
 *     COLLISION  the same const NAME in 2+ files with DIFFERENT values — one name, two meanings.
 *     `--ratchet` (the gate form) fails on any COPY/COLLISION group NOT in
 *     checks/config/const-single-source-baseline.json, on any group that has SPREAD to a new file,
 *     and on any STALE entry (fixed, or naming a file that no longer holds it). `--inventory`
 *     prints the whole inventory and `--census` prints the counts; both sit behind an explicit
 *     verb, because a bare run is the GATE here and a non-gating default is how a mis-invocation
 *     reads as a pass.
 *
 * DENOMINATOR, FROM THE SOURCE (§24), never a hand list. Every `const val` under
 * gateway/{module}/src/main at any depth is parsed off disk (1184 today), and the Knob plane is
 * parsed out of Knob.kt's enum entries (33 entries, 14 with a numeric default). A file added
 * tomorrow, or a Knob added tomorrow, is in scope with no edit to this checker. Three guards refuse
 * a vacuous pass: zero source files is a failure, zero parsed consts is a failure, and the parsed
 * count must equal the count of `const val` lines in the comment-stripped tree — a parser that has
 * drifted off the source cannot be trusted to report an absence.
 *
 * VALUES ARE COMPARED NORMALISED, not textually, and that is not cosmetic. Measured on this tree, a
 * textual comparison filed these as "different values" and would have mis-classed every one of them
 * as COLLISION: BOLD/RED/GREEN/DIM/CYAN/RESET/YELLOW (a raw ESC byte in one file, "\\u001B" in the
 * other — the SAME string), MILLIS_PER_SECOND (1000L vs 1_000L), MS_PER_S (1000 / 1000L / 1_000L),
 * BYTE_MASK (0xFF vs 0xff), TTL_MS (30 * 60 * 1000L vs 30L * 60 * 1000). Normalisation decodes
 * \\uXXXX escapes, drops digit separators and numeric type suffixes, lowercases hex digits, and
 * collapses whitespace — so two spellings of one value are one value.
 *
 * WHAT IS NOT CAUGHT, stated rather than implied.
 *   A duplicate with a DIFFERENT name and the same value — RATE_LIMITED vs RATE_LIMIT_STATUS vs
 *   HTTP_TOO_MANY, all 429. Value-only matching over 619 numeric consts is mostly noise (every
 *   `= 8` in the tree would pair with every other), so the HTTP status family — the one place where
 *   that shape was dense and dangerous — gets its own structural wall instead:
 *   quality/rules/kotlin/kt-http-status-single-source.yml. The general case stays open by choice.
 *   A same-meaning duplicate nobody has noticed yet. NAMED-SCAR is a list of the ones the audit
 *   named; a ninth one sits in the ratchet baseline until a human reads it and promotes it. That is
 *   the honest bound of a graded wall: the ratchet stops the tree growing, the list drives the fix.
 *   A multi-line declaration's value is read from its continuation line, but an expression spanning
 *   three or more lines is normalised as its first two. Seven declarations continue today, all of
 *   them strings; none is numeric.
 *   Non-`const` `val` declarations. The `const` modifier is what makes a value a compile-time
 *   constant with a declaration site worth single-sourcing; a computed `val` is a different subject.
 *   A Knob shadow more than one qualifier away from its Knob's name (a local `TIMEOUT_MS` against
 *   Knob.UPSTREAM_TIMEOUT_MS). The token bound is what keeps that detector from pairing every
 *   `= 0L` in the tree with Knob.USAGE_WARN_TOKENS_5H; see knob_shadows() for the measurement.
 *   An equality comment whose counterpart is a SINGLE-token name, or is not a const at all —
 *   ClientAuthProvider.kt:21's "MUST stay equal to [AuthKind.Client.wire]" names an enum property,
 *   and a test (LaunchSpecClientAuthTest) already pins it, so that obligation is walled elsewhere.
 *
 * WHY ITS OWN PARSER rather than importing one: no checker under checks/ imports another (see
 * checks/config/quirks-keys-documented.ts's own note on the same decision) — a shared parser makes
 * one wall's widening another wall's silent behaviour change, and each wall is supposed to be
 * readable on its own. The parsing here is line-based and comment/string aware.
 *
 * SELFTEST. `--selftest` builds temp trees and proves BOTH directions: GREEN on a compliant tree
 * (one declaration + an import), on the BORING cases (exactly one const; consts but none of the
 * shapes), on an explanatory comment that asserts nothing, on a token-unrelated Knob twin, and on a
 * BASELINED copy under --ratchet; RED BY NAME on a synthetic duplicate NOT in the baseline, on a
 * baselined group that spread to a new file, on a STALE baseline entry, on a NAMED-SCAR copy even
 * when it IS baselined, on a synthetic COLLISION, on a must-stay-equal comment pair, on a Knob
 * shadow, on a NAMED-SCAR entry that no longer describes a real duplicate, on a parse yielding zero
 * consts, and on a parsed count that disagrees with the source.
 *
 * Usage:
 *     bun checks/const-single-source.ts --ratchet [--root <path>]
 *     bun checks/const-single-source.ts --inventory [--root <path>]
 *     bun checks/const-single-source.ts --census [--root <path>]
 *     bun checks/const-single-source.ts --record [--root <path>]
 *     bun checks/const-single-source.ts --selftest
 *
 * A BARE RUN IS `--ratchet` — the one gating mode, so there is no non-gating default to mis-invoke.
 * The original script's bare form ran the INVENTORY, which exits 0 on a tree whose ratchet plane
 * has grown; that is the defect this port fixes, and the fix is that bare falls through to the gate
 * rather than to the inventory. `--record` authors the baseline and is therefore never reachable by
 * leaving a flag off a command line.
 */
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// parents[1]: this file lives at checks/, so the repo root is one level up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

// The name this checker gives itself in its own findings — the port of Python's Path(__file__).name.
const THIS_FILE = "const-single-source.ts";

// restructure PR 3: :client is the first module to live outside gateway/, so the production
// universe is no longer one `gateway/*` pattern. A source root this checker stops walking is a
// denominator that shrinks in silence, which is the one failure every ratchet here exists to
// prevent — so the list names every §2.2 module home, the ones that exist and the ones the next
// module commits create (a glob over an absent directory matches nothing, so the denominator can
// only grow), until PR 5 hands these checkers the build-derived source units of tools/gate.
const MAIN_GLOBS = [
  "gateway/*/src/main/**/*.kt", "client/src/main/**/*.kt", "core/src/main/**/*.kt", "upstream/src/main/**/*.kt",
  "dialects/*/src/main/**/*.kt", "providers/*/src/main/**/*.kt", "daemon/*/src/main/**/*.kt", "app/src/main/**/*.kt",
  "quality/*/src/main/**/*.kt",
];
const KNOB_REL = "core/src/main/kotlin/splice/core/config/Knob.kt";
const BASELINE_REL = "checks/config/const-single-source-baseline.json";

// THE NAMED SCARS — the duplicated names the 2026-09-17 audit identified as ONE meaning, each with
// the reason it is one. Red regardless of the ratchet baseline; this list IS the fix row's
// checklist. It is the only hand-authored list in this file, so it is cross-checked: an entry that
// no longer names a real COPY/COLLISION fails as STALE, and a reason left blank fails by name.
// Adding a name here is a claim that the two declarations must agree — write why, or do not add it.
// V4-122 EMPTIED THIS LIST, and that is the goal rather than an omission: every one of the six
// names it held — DEFAULT_MAX_CONTINUATIONS, ERR_BODY_CAP, BACKOFF_BASE_MS, EPOCH_MILLIS_FLOOR,
// ERR_SNIPPET, DEPTH_CAP — had its duplication RESOLVED rather than baselined, so each entry became
// STALE by this file's own rule and was deleted. The list is the fix row's checklist, and a
// checklist that keeps ticked items is unearned room. It stays here, empty, because the mechanism is
// what enforces the class: adding a name is a claim that two declarations must agree.
let NAMED_SCARS: Record<string, string> = {};

const DECL =
  /^[ \t]*(?:(?:public|internal|private|protected)\s+)?const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[^=]+?)?\s*=[ \t]*(.*)$/;
const CONST_VAL_LINE = /\bconst\s+val\b/;

// A bare numeric token: decimal, hex, or float, with optional digit separators and type suffix.
const NUM_TOKEN = /^(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*(?:\.[0-9_]+)?(?:[eE][-+]?[0-9]+)?)[LlFfDdUu]*$/;

// An equality obligation written in prose. Each of these is a sentence a human wrote instead of a
// wall; the detector needs one of them AND a named counterpart before it fires. The SOURCE STRING
// is kept beside each regex because the finding quotes it back: Python printed p.pattern, so a port
// that re-worded a pattern would change the message.
const EQUALITY_PHRASES: { source: string; re: RegExp }[] = [
  {
    source: "must\\s+(?:stay|remain|be\\s+kept)\\s+(?:equal|identical|the\\s+same|in\\s+sync)",
    re: /must\s+(?:stay|remain|be\s+kept)\s+(?:equal|identical|the\s+same|in\s+sync)/i,
  },
  { source: "must\\s+match", re: /must\s+match/i },
  { source: "must\\s+(?:be\\s+)?the\\s+same\\s+as", re: /must\s+(?:be\s+)?the\s+same\s+as/i },
  { source: "kept?\\s+in\\s+sync", re: /kept?\s+in\s+sync/i },
  { source: "in\\s+sync\\s+with", re: /in\s+sync\s+with/i },
  { source: "same\\s+value\\s+as", re: /same\s+value\s+as/i },
  { source: "mirror(?:s|ing|ed)?\\b", re: /mirror(?:s|ing|ed)?\b/i },
];
// The counterpart an equality comment must NAME, as a multi-token ALL_CAPS identifier or a
// Knob.NAME. Multi-token is what separates an identifier from prose: detekt's TopLevelPropertyNaming
// makes every package-scope const in this tree SCREAMING_SNAKE, and these comments are written in
// English that capitalises words for emphasis — "the CLIENT-FACING deadline", "MUST stay equal".
// Measured: requiring an underscore drops CLIENT and KIND (prose) from two findings' counterpart
// lists while keeping MAX_RATE_LIMIT_COOLDOWN_MS and RETENTION_MS (the real ones).
const NAMED_CONST = /\b(?:Knob\.)?([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\b/g;

const UNICODE_ESCAPE = /\\[uU]([0-9a-fA-F]{4})/g;

/** Python's str.splitlines(): splits on every line boundary AND drops the trailing empty line.
 *
 *  Two traps, both measured rather than reasoned. FIRST, Python's boundary set is wider than a
 *  newline, and it does NOT count a trailing newline as an extra empty final line — every Kotlin
 *  file ends with one. SECOND, and this is the sharp one: the two Unicode separators below are
 *  built with String.fromCharCode, NEVER written as escapes. A regex literal carrying a literal
 *  U+2028 or U+2029 is itself a JS line terminator and takes the whole file down with a syntax
 *  error, and a character-class RANGE written with escapes lands as raw control characters in the
 *  source. Anything non-ASCII in this file is constructed, never typed. */
const PY_LINE_BOUNDARY = new RegExp(
  "\\r\\n|[" +
    "\\n\\r\\v\\f\\x1c\\x1d\\x1e\\x85" +
    String.fromCharCode(0x2028, 0x2029) +
    "]",
);

function pySplitlines(text: string): string[] {
  if (text === "") return [];
  const parts = text.split(PY_LINE_BOUNDARY);
  if (parts.length > 0 && parts[parts.length - 1] === "") parts.pop();
  return parts;
}

/** Python's str.rstrip(",") — every trailing comma, not one. */
const rstripComma = (text: string): string => text.replace(/,+$/, "");

/** Python's str.strip() — Unicode whitespace at both ends. */
const pyStrip = (text: string): string => text.replace(/^\s+/, "").replace(/\s+$/, "");

class Const {
  readonly name: string;
  readonly rel: string;
  readonly line: number;
  readonly value: string;
  readonly comment: string;

  constructor(name: string, rel: string, line: number, raw: string, comment: string) {
    this.name = name;
    this.rel = rel;
    this.line = line;
    this.value = normalise(raw);
    this.comment = comment;
  }

  get where(): string {
    return `${this.rel}:${this.line}`;
  }
}

/** Drop a trailing `//` comment, respecting string literals — a URL's `//` is not a comment. */
function stripLineComment(text: string): string {
  let inString = false;
  let quote = "";
  let escape = false;
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
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
    if (ch === "/" && i + 1 < text.length && text[i + 1] === "/") return text.slice(0, i);
    i += 1;
  }
  return text;
}

/** One value, one spelling. See the docstring's VALUES ARE COMPARED NORMALISED. */
function normalise(raw: string): string {
  let text = rstripComma(pyStrip(stripLineComment(raw)));
  text = text.replace(UNICODE_ESCAPE, (_m, hex: string) => String.fromCodePoint(parseInt(hex, 16)));
  // digit separators and numeric type suffixes are spelling, not value
  text = text.replace(/(?<=[0-9])_(?=[0-9])/g, "");
  text = text.replace(/(?<=[0-9])[LlFfDdUu]+\b/g, "");
  text = text.replace(/0[xX]([0-9a-fA-F]+)/g, (_m, digits: string) => "0x" + digits.toLowerCase());
  return pyStrip(text.replace(/\s+/g, " "));
}

/** The contiguous comment block immediately above lines[index], plus its trailing comment.
 *
 *  Contiguity is the point: a blank line between a comment and a declaration means the comment
 *  belongs to whatever is above it, and reading it as this declaration's reason would credit the
 *  wrong constant. */
function commentAbove(lines: string[], index: number): string {
  const parts: string[] = [];
  let j = index - 1;
  while (j >= 0) {
    const stripped = pyStrip(lines[j]);
    if (
      stripped.startsWith("//") ||
      stripped.startsWith("*") ||
      stripped.startsWith("/*") ||
      stripped.endsWith("*/")
    ) {
      parts.push(stripped.replace(/^\/\*+|^\*+\/?|^\/\/|\*\/$/g, ""));
      j -= 1;
      continue;
    }
    break;
  }
  parts.reverse();
  const trailing = lines[index];
  const cut = stripLineComment(trailing);
  if (cut.length < trailing.length) parts.push(trailing.slice(cut.length + 2));
  return pyStrip(parts.map((part) => pyStrip(part)).join(" "));
}

/** (declarations, raw `const val` line count) for one file. */
function parseFile(rel: string, text: string): { found: Const[]; rawCount: number } {
  const lines = pySplitlines(text);
  const found: Const[] = [];
  let rawCount = 0;
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    const stripped = pyStrip(line);
    if (stripped.startsWith("//") || stripped.startsWith("*")) continue;
    if (CONST_VAL_LINE.test(line)) rawCount += 1;
    const match = DECL.exec(line);
    if (match === null) continue;
    let value = pyStrip(match[2]);
    // continuation form: `const val X =` with the value on the next line
    if (!value) value = i + 1 < lines.length ? pyStrip(lines[i + 1]) : "";
    found.push(new Const(match[1], rel, i + 1, value, commentAbove(lines, i)));
  }
  return { found, rawCount };
}

/** Every main-source declaration, plus the problems that make a report untrustworthy. */
function parseTree(root: string): { consts: Const[]; problems: string[] } {
  const problems: string[] = [];
  const consts: Const[] = [];
  let rawTotal = 0;
  const files = MAIN_GLOBS
    .flatMap((p) => [...new Bun.Glob(p).scanSync({ cwd: root, followSymlinks: true })])
    .sort();
  if (files.length === 0) {
    return {
      consts: [],
      problems: [`no main sources matched ${MAIN_GLOBS.join(", ")} under ${root} — the denominator is absent`],
    };
  }
  for (const rel of files) {
    const parsed = parseFile(rel, readFileSync(join(root, rel), "utf8"));
    consts.push(...parsed.found);
    rawTotal += parsed.rawCount;
  }
  if (consts.length === 0) {
    problems.push(
      `parsed 0 const declarations from ${files.length} main source file(s) — refusing to pass ` +
        "vacuously, because a green over an empty denominator is what this wall exists to prevent",
    );
  }
  if (rawTotal !== consts.length) {
    problems.push(
      `parsed ${consts.length} declarations but the tree holds ${rawTotal} \`const val\` lines — ` +
        "the parser and the source disagree, so no finding or absence from this run can be trusted",
    );
  }
  return { consts, problems };
}

/** The text inside the parens opening at `start`, string- and comment-aware. */
function balanced(text: string, start: number): string | null {
  let depth = 0;
  let bodyStart: number | null = null;
  let i = start;
  let inString = false;
  let quote = "";
  let escape = false;
  while (i < text.length) {
    const ch = text[i];
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
    if (ch === "/" && i + 1 < text.length && text[i + 1] === "/") {
      const newline = text.indexOf("\n", i);
      i = newline < 0 ? text.length : newline;
      continue;
    }
    if (ch === "(") {
      depth += 1;
      if (depth === 1) bodyStart = i + 1;
    } else if (ch === ")") {
      depth -= 1;
      if (depth === 0 && bodyStart !== null) return text.slice(bodyStart, i);
    }
    i += 1;
  }
  return null;
}

/** Split on top-level commas, string-aware. */
function splitTopLevel(body: string): string[] {
  const parts: string[] = [];
  let buf: string[] = [];
  let depth = 0;
  let inString = false;
  let quote = "";
  let escape = false;
  for (const ch of body) {
    if (inString) {
      buf.push(ch);
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === quote) inString = false;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      buf.push(ch);
      continue;
    }
    if ("([{".includes(ch)) depth += 1;
    else if (")]}".includes(ch)) depth -= 1;
    if (ch === "," && depth === 0) {
      parts.push(buf.join(""));
      buf = [];
      continue;
    }
    buf.push(ch);
  }
  if (buf.length > 0) parts.push(buf.join(""));
  return parts.map((part) => pyStrip(part));
}

/** Knob name -> normalised numeric default, parsed from the enum entries. */
function knobDefaults(root: string): { knobs: Record<string, string>; problems: string[] } {
  const path = join(root, KNOB_REL);
  if (!existsSync(path)) return { knobs: {}, problems: [] };
  const text = readFileSync(path, "utf8");
  const entries = [...text.matchAll(/^ {4}([A-Z][A-Z0-9_]*)(?=\()/gm)];
  if (entries.length === 0) {
    return {
      knobs: {},
      problems: [`${KNOB_REL}: parsed 0 Knob entries — the Knob plane cannot be checked`],
    };
  }
  const out: Record<string, string> = {};
  for (const match of entries) {
    const openIndex = (match.index as number) + match[0].length;
    const body = balanced(text, openIndex);
    if (body === null) continue;
    const positional = splitTopLevel(body).filter(
      (arg) => !/^[A-Za-z_][A-Za-z0-9_]*\s*=[^=]/.test(arg),
    );
    if (positional.length < 4) continue;
    const fallback = normalise(positional[3].replace(/\/\/[^\n]*/g, ""));
    if (NUM_TOKEN.test(fallback)) out[match[1]] = fallback;
  }
  return { knobs: out, problems: [] };
}

/** Significant name tokens: DEFAULT_ is a role marker and single letters carry no meaning. */
function tokens(name: string): Set<string> {
  return new Set(
    name.split("_").filter((part) => part.length > 1 && !["DEFAULT", "THE", "VAL"].includes(part)),
  );
}

// ── detectors ─────────────────────────────────────────────────────────────────────────────────

/** Python's repr() of a list of strings: `['a', 'b']`. */
function pyReprList(items: string[]): string {
  return `[${items.map((item) => `'${item}'`).join(", ")}]`;
}

/** One duplicated NAME: its class, its declarations, and the files it spans. */
class Group {
  readonly kind: string;
  readonly name: string;
  readonly members: Const[];

  constructor(kind: string, name: string, members: Const[]) {
    this.kind = kind;
    this.name = name;
    this.members = members;
  }

  /** The baseline key: class + name. Sorted files are the VALUE, so a spread is visible. */
  get key(): string {
    return `${this.kind} ${this.name}`;
  }

  get files(): string[] {
    return [...new Set(this.members.map((c) => c.rel))].sort();
  }

  /** Sites are ordered by (rel, line) — Python's sort key, so the message is stable. */
  get sites(): string {
    return [...this.members]
      .sort((a, b) => (a.rel === b.rel ? a.line - b.line : a.rel < b.rel ? -1 : 1))
      .map((c) => c.where)
      .join(", ");
  }

  get values(): string[] {
    return [...new Set(this.members.map((c) => c.value))].sort();
  }

  describe(): string {
    if (this.kind === "COPY") {
      return `${this.name} = ${this.values[0]} is declared in ${this.files.length} files (${this.sites})`;
    }
    return (
      `${this.name} is declared in ${this.files.length} files with ${this.values.length} different ` +
      `values ${pyReprList(this.values)} — one name, two meanings (${this.sites})`
    );
  }
}

/** Every name declared in 2+ FILES, classed COPY (one normalised value) or COLLISION. */
function duplicates(consts: Const[]): Group[] {
  const byName = new Map<string, Const[]>();
  for (const c of consts) {
    const list = byName.get(c.name);
    if (list === undefined) byName.set(c.name, [c]);
    else list.push(c);
  }
  const out: Group[] = [];
  for (const name of [...byName.keys()].sort()) {
    const members = byName.get(name) as Const[];
    if (new Set(members.map((c) => c.rel)).size < 2) continue;
    const kind = new Set(members.map((c) => c.value)).size === 1 ? "COPY" : "COLLISION";
    out.push(new Group(kind, name, members));
  }
  return out;
}

/** (const, phrase, counterparts) for consts whose adjacent comment ASSERTS an equality. */
function equalityComments(consts: Const[]): { konst: Const; phrase: string; named: string[] }[] {
  const names = new Set(consts.map((c) => c.name));
  const out: { konst: Const; phrase: string; named: string[] }[] = [];
  for (const c of consts) {
    if (!c.comment) continue;
    const hit = EQUALITY_PHRASES.find((p) => p.re.test(c.comment));
    if (hit === undefined) continue;
    // distinct, in first-seen order — Python's dict.fromkeys
    const named = [...new Set([...c.comment.matchAll(NAMED_CONST)].map((m) => m[1]))].filter(
      (found) => found !== c.name && names.has(found),
    );
    if (named.length > 0) out.push({ konst: c, phrase: hit.source, named });
  }
  return out;
}

/** (const, knob, value) where a local literal default forks a Knob's operator-facing default. */
function knobShadows(
  consts: Const[],
  knobs: Record<string, string>,
): { konst: Const; knob: string; value: string }[] {
  const out: { konst: Const; knob: string; value: string }[] = [];
  for (const c of consts) {
    if (!NUM_TOKEN.test(c.value)) continue;
    const local = tokens(c.name);
    if (local.size === 0) continue;
    for (const knob of Object.keys(knobs).sort()) {
      const fallback = knobs[knob];
      const knobTokens = tokens(knob);
      // Subset AND within one qualifier. The bare subset test pairs any const whose name is
      // built only of generic unit words with any knob that also carries them: measured, it
      // filed UpgradeProcess.kt's DEFAULT_TIMEOUT_MS = 300_000L (a `gh attestation verify`
      // subprocess budget, tokens {TIMEOUT, MS}) against Knob.FIRST_BYTE_TIMEOUT_MS
      // ({FIRST, BYTE, TIMEOUT, MS}) — same number, unrelated subject. Two names for ONE
      // value differ by at most one qualifier (DEFAULT_WARN_PCT vs USAGE_WARN_PCT, +USAGE;
      // DEFAULT_MAX_TIER_N vs FOLD_MAX_TIER, +FOLD; FOLD_DEFAULT_MAX_CONTINUE vs
      // FOLD_MAX_CONTINUE, +nothing), so that is the bound.
      const subset = [...local].every((token) => knobTokens.has(token));
      const extra = [...knobTokens].filter((token) => !local.has(token)).length;
      if (c.value === fallback && subset && extra <= 1) {
        out.push({ konst: c, knob, value: fallback });
        break;
      }
    }
  }
  return out;
}

// ── the strict plane ──────────────────────────────────────────────────────────────────────────

/** EQUAL-BY-COMMENT, KNOB-SHADOW and NAMED-SCAR. No baseline reaches any of these. */
function strictProblems(consts: Const[], knobs: Record<string, string>, groups: Group[]): string[] {
  const problems: string[] = [];

  for (const { konst, phrase, named } of equalityComments(consts)) {
    problems.push(
      `EQUAL-BY-COMMENT: ${konst.where} ${konst.name} = ${konst.value} — its comment asserts ` +
        `an equality (/${phrase}/) with ${named.join(", ")}. A comment is not a wall: make one ` +
        "of them the declaration and import it",
    );
  }

  for (const { konst, knob, value } of knobShadows(consts, knobs)) {
    problems.push(
      `KNOB-SHADOW: ${konst.where} ${konst.name} = ${value} duplicates Knob.${knob}'s default ` +
        `(${value}) — the Knob is the operator-facing single source; read it instead of ` +
        "re-declaring its default",
    );
  }

  const byName = new Map(groups.map((group) => [group.name, group]));
  for (const name of Object.keys(NAMED_SCARS).sort()) {
    const reason = NAMED_SCARS[name];
    const group = byName.get(name);
    if (group === undefined) {
      problems.push(
        `NAMED-SCAR STALE: ${name} is on the NAMED_SCARS list in ${THIS_FILE} ` +
          "but is no longer declared in 2+ files — delete the entry. A named scar held past " +
          "its fix is unearned room for the next duplicate to hide in",
      );
      continue;
    }
    if (!pyStrip(reason)) {
      problems.push(
        `NAMED-SCAR: ${name} is listed with NO reason — a named scar without a written ` +
          "reason is an absence wearing a label; say why it is one meaning, or remove it",
      );
      continue;
    }
    problems.push(
      `NAMED-SCAR (${group.kind}): ${group.describe()} — ${reason}. One meaning, so one ` +
        "declaration: red regardless of the ratchet baseline",
    );
  }

  return problems;
}

// ── the ratchet plane ─────────────────────────────────────────────────────────────────────────

function loadBaseline(root: string): {
  baseline: Record<string, unknown> | null;
  problems: string[];
} {
  const path = join(root, BASELINE_REL);
  if (!existsSync(path)) {
    return {
      baseline: null,
      problems: [`${BASELINE_REL}: missing — the ratchet has no recorded census to hold the tree to`],
    };
  }
  let data: Record<string, unknown>;
  try {
    data = JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
  } catch (exc) {
    return {
      baseline: null,
      problems: [
        `${BASELINE_REL}: unreadable (${exc}) — a ratchet that cannot read its baseline cannot gate`,
      ],
    };
  }
  for (const key of ["recorded", "total", "denominator", "groups"]) {
    if (!(key in data)) {
      return { baseline: null, problems: [`${BASELINE_REL}: missing required key '${key}'`] };
    }
  }
  return { baseline: data, problems: [] };
}

const sameList = (a: string[], b: string[]): boolean =>
  a.length === b.length && a.every((item, index) => item === b[index]);

/** GROWTH and STALE over the COPY/COLLISION plane, keyed by (class, name) with sorted files. */
function ratchetProblems(groups: Group[], baseline: Record<string, unknown>): string[] {
  const problems: string[] = [];
  const recordedGroups = baseline.groups as Record<string, string[]>;
  const recorded = new Map<string, string[]>(
    Object.keys(recordedGroups).map((key) => [key, [...recordedGroups[key]].sort()]),
  );
  const measured = new Map<string, Group>(groups.map((group) => [group.key, group]));

  for (const key of [...measured.keys()].sort()) {
    const group = measured.get(key) as Group;
    // a NAMED_SCARS name is handled by the strict plane; reporting it twice buries the reason
    if (group.name in NAMED_SCARS) continue;
    const was = recorded.get(key);
    if (was === undefined) {
      problems.push(
        `GROWTH (${group.kind}): ${group.describe()} — not in the baseline. Give it one ` +
          `declaration and an import, or record it in ${BASELINE_REL} with the reason it is ` +
          "two independent values",
      );
      continue;
    }
    const now = group.files;
    if (sameList(now, was)) continue;
    const spread = now.filter((rel) => !was.includes(rel));
    if (spread.length > 0) {
      problems.push(
        `GROWTH (${group.kind}): ${group.name} has SPREAD to ${spread.join(", ")} — the ` +
          `baseline records ${was.length} file(s), the tree now has ${now.length} (${group.sites})`,
      );
    } else {
      const dropped = was.filter((rel) => !now.includes(rel)).join(", ");
      problems.push(
        `STALE (${group.kind}): ${group.name} now spans ${now.length} file(s) but ` +
          `${BASELINE_REL} records ${was.length} — ${dropped} ` +
          "no longer declares it; lower the entry to record the win",
      );
    }
  }

  for (const key of [...recorded.keys()].sort()) {
    const was = recorded.get(key) as string[];
    const name = key.includes(" ") ? key.slice(key.indexOf(" ") + 1) : key;
    if (name in NAMED_SCARS) {
      problems.push(
        `STALE: ${BASELINE_REL} records ${key}, but ${name} is on the NAMED_SCARS strict ` +
          "list — a name cannot be both baselined and strict; delete the baseline entry",
      );
      continue;
    }
    if (!measured.has(key)) {
      problems.push(
        `STALE: ${BASELINE_REL} records ${key} in ${was.length} file(s), but the tree no ` +
          "longer declares it in 2+ files — delete the entry. A baseline held above the " +
          "measurement is unearned room for the next duplicate to hide in",
      );
    }
  }

  return problems;
}

/** The baseline document for the tree as it stands. Authors the JSON; never read by the gate. */
function record(root: string): Record<string, unknown> {
  const { consts, problems } = parseTree(root);
  if (problems.length > 0) {
    process.stderr.write("; ".join(problems) + "\n");
    process.exit(1);
  }
  const groups = duplicates(consts).filter((group) => !(group.name in NAMED_SCARS));
  return {
    _note:
      "AUTHORED BY checks/const-single-source.ts --record, MEASURED never estimated. One entry " +
      "per duplicated const NAME, keyed '<CLASS> <NAME>' with the sorted files that declare " +
      "it. COPY = same normalised value in 2+ files; COLLISION = one name, different values. " +
      "These are the LOW-certainty classes: the 2026-09-17 review established that most of " +
      "them are file-local names for different wires whose values coincide, and that making " +
      "one import the other would ADD coupling. So they are held, not failed: --ratchet fails " +
      "on a group that is NOT here, on a group that has SPREAD to a new file, and on a STALE " +
      "entry. The high-certainty classes (EQUAL-BY-COMMENT, KNOB-SHADOW, NAMED-SCAR) are " +
      "STRICT and no entry here can reach them — a NAMED_SCARS name recorded here is itself " +
      "an error.",
    recorded: "2026-09-17",
    denominator: consts.length,
    total: groups.length,
    groups: Object.fromEntries(
      [...groups]
        .sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0))
        .map((group) => [group.key, group.files]),
    ),
  };
}

// ── entry points ──────────────────────────────────────────────────────────────────────────────

/** Everything every mode needs, plus the problems that make the whole run untrustworthy. */
function instrument(root: string): {
  consts: Const[];
  knobs: Record<string, string>;
  groups: Group[];
  problems: string[];
} {
  const { consts, problems } = parseTree(root);
  if (problems.length > 0) return { consts: [], knobs: {}, groups: [], problems };
  const { knobs, problems: knobProblems } = knobDefaults(root);
  return { consts, knobs, groups: duplicates(consts), problems: knobProblems };
}

/** The full inventory: strict findings AND every COPY/COLLISION, baseline ignored. */
function inventory(root: string): { code: number; lines: string[] } {
  const out: string[] = [];
  const { consts, knobs, groups, problems } = instrument(root);
  if (problems.length > 0) {
    out.push("FAIL: const-single-source — the instrument is untrustworthy:");
    out.push(...problems.map((problem) => `  ✗ ${problem}`));
    return { code: 2, lines: out };
  }

  const strict = strictProblems(consts, knobs, groups);
  const held = groups.filter((group) => !(group.name in NAMED_SCARS));

  out.push(`STRICT — ${strict.length} finding(s), no baseline reaches these:`);
  out.push(...strict.map((problem) => `  ✗ ${problem}`));
  out.push("");
  out.push(`RATCHETED — ${held.length} duplicated name(s), held by ${BASELINE_REL}:`);
  for (const group of held) out.push(`  · ${group.kind}: ${group.describe()}`);
  return { code: strict.length > 0 ? 1 : 0, lines: out };
}

/** Python's `{:>N}` on an int: pad to N with spaces, never truncate. */
const padStartNum = (value: number, width: number): string => String(value).padStart(width);

/** The GATE form: strict findings always, plus growth/stale over the held plane. */
function ratchet(root: string): { code: number; lines: string[] } {
  const out: string[] = [];
  const { consts, knobs, groups, problems } = instrument(root);
  const { baseline, problems: baselineProblems } = loadBaseline(root);
  const allProblems = [...problems, ...baselineProblems];
  if (allProblems.length > 0 || baseline === null) {
    out.push("FAIL: const-single-source ratchet — the instrument is untrustworthy:");
    out.push(...allProblems.map((problem) => `  ✗ ${problem}`));
    return { code: 2, lines: out };
  }

  const strict = strictProblems(consts, knobs, groups);
  const held = groups.filter((group) => !(group.name in NAMED_SCARS));
  const moved = ratchetProblems(groups, baseline);

  out.push(
    `CONST SINGLE SOURCE — baseline recorded ${baseline.recorded as string}; STRICT classes are not ` +
      "baselined at all",
  );
  out.push(
    `  ${"const declarations".padEnd(26)} denominator ${padStartNum(baseline.denominator as number, 5)}` +
      `   measured ${padStartNum(consts.length, 5)}`,
  );
  out.push(
    `  ${"STRICT findings".padEnd(26)} expected        0   measured ${padStartNum(strict.length, 5)}` +
      "   [GATED, no baseline]",
  );
  out.push(
    `  ${"COPY/COLLISION names".padEnd(26)} baseline    ${padStartNum(baseline.total as number, 5)}` +
      `   measured ${padStartNum(held.length, 5)}   [RATCHETED]`,
  );
  out.push(
    `  ${"NAMED_SCARS".padEnd(26)} listed      ${padStartNum(Object.keys(NAMED_SCARS).length, 5)}`,
  );

  if (consts.length !== baseline.denominator) {
    out.push(
      `  NOTE: the declaration denominator moved ${baseline.denominator} -> ${consts.length}; ` +
        "the gate reads the per-group entries, not the total, so this is reported and not gated",
    );
  }

  const failures = [...strict, ...moved];
  if (failures.length > 0) {
    out.push("");
    out.push(`FAIL: const-single-source — ${failures.length} problem(s):`);
    out.push(...failures.map((failure) => `  ✗ ${failure}`));
    return { code: 1, lines: out };
  }

  out.push("");
  out.push(
    `OK: const-single-source — 0 strict findings, and the ${held.length} held duplicate(s) are ` +
      `exactly the ${baseline.recorded as string} baseline`,
  );
  return { code: 0, lines: out };
}

/** Python's json.dumps(value, indent=2).
 *
 *  ensure_ascii=True is the Python DEFAULT and it is LOAD-BEARING rather than cosmetic: every
 *  non-ASCII character is written as an escape, and the shipped baseline is pure ASCII with its
 *  em-dashes spelled that way (measured: 0 bytes above 127, and the _note's em-dash is an escape).
 *  A port that let JSON.stringify emit the literal character would rewrite the file's bytes on a
 *  --record round-trip and report a whole-line diff where nothing had changed.
 *
 *  Iterating UTF-16 code units, not code points: Python escapes each SURROGATE half of a non-BMP
 *  character separately, and a code-point loop would emit a single 5-digit escape instead. */
function pyJsonDumps(value: unknown): string {
  const text = JSON.stringify(value, null, 2);
  const parts: string[] = [];
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i);
    parts.push(code > 127 ? "\\u" + code.toString(16).padStart(4, "0") : text[i]);
  }
  return parts.join("");
}

/** Python's `{:>3}` for the census column. */
const pad3 = (value: number): string => String(value).padStart(3);

function census(root: string): void {
  const { consts, knobs, groups, problems } = instrument(root);
  for (const problem of problems) process.stdout.write(`  UNTRUSTED: ${problem}\n`);
  if (problems.length > 0) return;
  const files = new Set(consts.map((c) => c.rel)).size;
  const scars = groups.filter((group) => group.name in NAMED_SCARS);
  const held = groups.filter((group) => !(group.name in NAMED_SCARS));
  process.stdout.write(
    `const-single-source: ${consts.length} const declarations across ${files} main source files\n`,
  );
  process.stdout.write(`  Knob plane: ${Object.keys(knobs).length} entries with a numeric default\n`);
  process.stdout.write("  STRICT:\n");
  process.stdout.write(`    EQUAL-BY-COMMENT  ${pad3(equalityComments(consts).length)} declarations\n`);
  process.stdout.write(`    KNOB-SHADOW       ${pad3(knobShadows(consts, knobs).length)} declarations\n`);
  process.stdout.write(
    `    NAMED-SCAR        ${pad3(scars.length)} of ${Object.keys(NAMED_SCARS).length} listed names, found in the tree\n`,
  );
  process.stdout.write("  RATCHETED:\n");
  process.stdout.write(`    COPY              ${pad3(held.filter((g) => g.kind === "COPY").length)} names\n`);
  process.stdout.write(
    `    COLLISION         ${pad3(held.filter((g) => g.kind === "COLLISION").length)} names\n`,
  );
}

// ── selftest ──────────────────────────────────────────────────────────────────────────────────

const KNOB_SOURCE = `package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    public val default: Any?,
    public val restartRequired: Boolean = false,
) {
    USAGE_WARN_PCT("usageWarnPct", KnobKind.NUMBER, listOf("SPLICE_USAGE_WARN_PCT"), 80L),
    FOLD_MAX_TIER(
        "foldMaxTier",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FOLD_MAX_TIER"),
        // a comment between the args, which a naive positional split would count as one
        6L,
        restartRequired = true,
    ),
}
`;

// The compliant tree: one declaration per value, the second file imports it, and a comment that
// merely EXPLAINS a number (no equality claim) is not a finding.
const COMPLIANT_A = `package splice.a

import splice.b.SHARED_CEILING_MS

// 120s starves a herd but lets a recovering account resume inside one client-retry cycle.
internal const val LOCAL_ONLY_MS = 45_000L

internal fun hold(): Long = SHARED_CEILING_MS
`;

const COMPLIANT_B = `package splice.b

internal const val SHARED_CEILING_MS = 120_000L
`;

// Exactly one const in the whole tree: the BORING case. Nothing to pair with, and the wall must
// say so with a count rather than going green on an empty denominator.
const BORING = `package splice.only

private const val ONE = 7
`;

const EMPTY = `package splice.nothing

internal fun f(): Int = 7
`;

// The parser-drift fixture: an ANNOTATED const. The line holds `const val`, so the raw count sees
// it, and DECL — which admits a visibility modifier and nothing else — does not. The guard must
// refuse the whole run rather than report an absence it cannot vouch for.
const DRIFT = `package splice.drift

private const val OK = 1

@Suppress("MagicNumber") private const val ANNOTATED = 3
`;

const DUP_A = 'package splice.a\n\nprivate const val SEAM_WIDTH = 8\n';
const DUP_B = 'package splice.b\n\nprivate const val SEAM_WIDTH = 8\n';
const DUP_C = 'package splice.c\n\nprivate const val SEAM_WIDTH = 8\n';
// same name, different value — and spelled so the normaliser must do its job for the COPY twin
const DUP_SPELLING_A = 'package splice.a\n\nprivate const val MS_PER_S = 1000L\n';
const DUP_SPELLING_B = 'package splice.b\n\nprivate const val MS_PER_S = 1_000\n';
const COLLIDE_A = 'package splice.a\n\nprivate const val SEAM_BOUND = 10\n';
const COLLIDE_B = 'package splice.b\n\nprivate const val SEAM_BOUND = 200\n';
// a NAMED_SCARS name, so the strict plane must fire even when the baseline records it
const SCAR_A = 'package splice.a\n\nprivate const val ERR_BODY_CAP = 8\n';
const SCAR_B = 'package splice.b\n\nprivate const val ERR_BODY_CAP = 8\n';

const EQUAL_COMMENT = `package splice.a

private const val MAX_RATE_LIMIT_COOLDOWN_MS = 120_000L

// Mirrors :upstream's MAX_RATE_LIMIT_COOLDOWN_MS, which is private to that module.
// The two must stay equal — the cooldown ceiling is when this gateway next lets a request through.
private const val MAX_CLIENT_HOLD_MS = 120_000L
`;

// An adjacent comment that explains a number without asserting an equality: must stay GREEN, or
// the detector is just a comment-length check.
const EXPLAINED_ONLY = `package splice.a

// 4 attempts matches the surveyed harness floor; the old default of 2 still failed turns on blips.
private const val UPSTREAM_ATTEMPTS = 4
`;

const KNOB_SHADOW_SRC = `package splice.a

private const val DEFAULT_WARN_PCT = 80
`;

// Same value as a Knob default but sharing no name token: must stay GREEN (see WHAT IS NOT CAUGHT).
const KNOB_UNRELATED = `package splice.a

private const val RETRY_SLOTS = 80
`;

const A_KT = "app/src/main/kotlin/splice/A.kt";
const B_KT = "core/src/main/kotlin/splice/B.kt";
const C_KT = "daemon/control/src/main/kotlin/splice/C.kt";
/** A fixture file's spelling in the arms -> the tree path it lands at. The paths carry the module
 *  homes (restructure PR 3 moves the modules out of gateway/ one by one, and the re-key that follows
 *  each move keeps A_KT/B_KT/C_KT true), so the writer never composes `gateway/<module>` itself. */
const FIXTURE_PATHS: Record<string, string> = { "app/A.kt": A_KT, "core/B.kt": B_KT, "control/C.kt": C_KT };

function mkdtempSync(): string {
  const dir = join(
    tmpdir(),
    `const-single-source-${process.pid}-${Math.random().toString(36).slice(2)}`,
  );
  mkdirSync(dir, { recursive: true });
  return dir;
}

function writeTree(
  root: string,
  files: Record<string, string>,
  baseline: Record<string, unknown> | null,
  knob = true,
): void {
  // every file an earlier arm may have written is removed first, wherever its module lives
  for (const rel of [...Object.values(FIXTURE_PATHS), KNOB_REL]) {
    const stale = join(root, rel);
    if (existsSync(stale)) rmSync(stale);
  }
  for (const rel of Object.keys(files).sort()) {
    const body = files[rel];
    const known = FIXTURE_PATHS[rel];
    if (known === undefined) throw new Error(`selftest fixture ${rel} has no tree path in FIXTURE_PATHS`);
    const target = join(root, known);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, body, "utf8");
  }
  if (knob) {
    const path = join(root, KNOB_REL);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, KNOB_SOURCE, "utf8");
  }
  const path = join(root, BASELINE_REL);
  mkdirSync(dirname(path), { recursive: true });
  if (baseline === null) {
    if (existsSync(path)) rmSync(path);
    return;
  }
  writeFileSync(path, pyJsonDumps(baseline), "utf8");
}

function base(groups: Record<string, string[]>, denominator = 0): Record<string, unknown> {
  return { recorded: "selftest", total: Object.keys(groups).length, denominator, groups };
}

/** The SHIPPED NAMED_SCARS list, checked without a tree: every entry reasoned and well-named.
 *
 *  The fixture plane below neutralises NAMED_SCARS so a temp tree that does not happen to contain
 *  the real tree's duplicates is not reported STALE eight times over — the STALE arm is a claim
 *  about the REAL list against the REAL tree, and checks/const-single-source-selftest.sh is where
 *  it is proven (its control runs the shipped list against the shipped source). This function is
 *  what stops that neutralising from also hiding an UNREASONED shipped entry.
 *
 *  AN EMPTY LIST IS A VALID STATE, and the guard that used to reject it was wrong in a way V4-122
 *  demonstrated rather than argued: it said an empty list makes the strict plane toothless, but the
 *  strict plane's teeth are the SOURCE-derived classes — EQUAL-BY-COMMENT, KNOB-SHADOW — which fire
 *  whether or not this list has entries. On the run that emptied it, EQUAL-BY-COMMENT still reddened
 *  the tree with the list empty, which is the proof. Worse, the guard made the goal unreachable: a
 *  row that FIXES every scar must delete its entries (a kept one reds as STALE), so 'all scars
 *  fixed' and 'list non-empty' could not both hold. The two arms that remain still catch the failure
 *  the guard was reaching for: a stale entry reds, and a blank reason reds. */
function shippedListProblems(): string[] {
  const problems: string[] = [];
  for (const name of Object.keys(NAMED_SCARS).sort()) {
    if (!pyStrip(NAMED_SCARS[name])) problems.push(`shipped NAMED_SCARS[${name}] carries no reason`);
    if (!/^[A-Z][A-Z0-9_]*$/.test(name)) {
      problems.push(`shipped NAMED_SCARS[${name}] is not a const name`);
    }
  }
  return problems;
}

function selftest(): number {
  const failures: string[] = [];

  // Proven BEFORE the list is neutralised for the fixture plane. See shippedListProblems().
  failures.push(...shippedListProblems());
  const shipped = NAMED_SCARS;
  NAMED_SCARS = {};

  const run = (
    mode: (root: string) => { code: number; lines: string[] },
    files: Record<string, string>,
    baseline: Record<string, unknown> | null,
  ): { code: number; text: string } => {
    const root = mkdtempSync();
    try {
      writeTree(root, files, baseline);
      const { code, lines } = mode(root);
      return { code, text: lines.join("\n") };
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  };

  const expect = (
    label: string,
    wantGreen: boolean,
    files: Record<string, string>,
    baseline: Record<string, unknown> | null,
    ...needles: string[]
  ): void => {
    const { code, text } = run(ratchet, files, baseline);
    if (wantGreen && code !== 0) failures.push(`${label} must be GREEN, got exit ${code}:\n${text}`);
    if (!wantGreen && code === 0) failures.push(`${label} must be RED, got exit 0:\n${text}`);
    for (const needle of needles) {
      if (!text.includes(needle)) failures.push(`${label} must report '${needle}', got:\n${text}`);
    }
  };

  const empty = base({});

  // ── the strict plane: no baseline reaches it ───────────────────────────────────────────────
  expect(
    "the compliant tree (one declaration + an import)",
    true,
    { "app/A.kt": COMPLIANT_A, "core/B.kt": COMPLIANT_B },
    empty,
  );
  expect(
    "the BORING case: exactly one const in the tree",
    true,
    { "app/A.kt": BORING },
    empty,
    "measured     1",
  );
  expect(
    "a comment that EXPLAINS without asserting an equality",
    true,
    { "app/A.kt": EXPLAINED_ONLY },
    empty,
  );
  expect(
    "a value equal to a Knob default but sharing no name token",
    true,
    { "app/A.kt": KNOB_UNRELATED },
    empty,
  );
  expect(
    "the scar: a comment asserting the two must stay equal",
    false,
    { "app/A.kt": EQUAL_COMMENT },
    empty,
    "EQUAL-BY-COMMENT",
    "MAX_CLIENT_HOLD_MS",
  );
  expect(
    "a local default shadowing a Knob default",
    false,
    { "app/A.kt": KNOB_SHADOW_SRC },
    empty,
    "KNOB-SHADOW",
    "USAGE_WARN_PCT",
  );

  // ── the ratchet plane ─────────────────────────────────────────────────────────────────────
  const dupFiles = { "app/A.kt": DUP_A, "core/B.kt": DUP_B };
  const dupKey = "COPY SEAM_WIDTH";
  const dupBaseline = base({ [dupKey]: [A_KT, B_KT] });

  expect(
    "1. a synthetic COPY that is NOT in the baseline",
    false,
    dupFiles,
    empty,
    "GROWTH (COPY)",
    "SEAM_WIDTH",
    "not in the baseline",
  );
  expect("2. the SAME COPY, recorded in the baseline", true, dupFiles, dupBaseline, "exactly the selftest baseline");
  expect(
    "3. a baselined COPY that SPREAD to a third file",
    false,
    { ...dupFiles, "control/C.kt": DUP_C },
    dupBaseline,
    "GROWTH (COPY)",
    "SPREAD",
    C_KT,
  );
  expect(
    "4. a STALE baseline entry (the duplicate is gone)",
    false,
    { "app/A.kt": DUP_A },
    dupBaseline,
    "STALE",
    dupKey,
    "no longer declares it in 2+ files",
  );
  expect(
    "5. a baselined COPY that lost one of three files",
    false,
    dupFiles,
    base({ [dupKey]: [A_KT, B_KT, C_KT] }),
    "STALE (COPY)",
    "lower the entry",
  );
  expect(
    "6. two spellings of ONE value are a COPY, not a COLLISION",
    false,
    { "app/A.kt": DUP_SPELLING_A, "core/B.kt": DUP_SPELLING_B },
    empty,
    "GROWTH (COPY)",
    "MS_PER_S",
  );
  expect(
    "7. a synthetic COLLISION not in the baseline",
    false,
    { "app/A.kt": COLLIDE_A, "core/B.kt": COLLIDE_B },
    empty,
    "GROWTH (COLLISION)",
    "SEAM_BOUND",
  );
  expect(
    "8. a COLLISION recorded in the baseline",
    true,
    { "app/A.kt": COLLIDE_A, "core/B.kt": COLLIDE_B },
    base({ "COLLISION SEAM_BOUND": [A_KT, B_KT] }),
  );

  // ── NAMED-SCAR outranks the baseline ──────────────────────────────────────────────────────
  // The entry is FIXTURE-ONLY now, and it has to be: V4-122 fixed every name the shipped list
  // held, so there is no shipped entry left to borrow and the old code — shipped["ERR_BODY_CAP"] —
  // raises KeyError. That is the instrument telling the truth about its own list rather than a
  // fixture bug: what these cases prove is the RULE (a listed name outranks the baseline), and the
  // shipped list's own well-formedness is checked separately, by the ratchet reding a STALE entry
  // or a blank reason.
  const scarFiles = { "app/A.kt": SCAR_A, "core/B.kt": SCAR_B };
  NAMED_SCARS = { ERR_BODY_CAP: "one error-body truncation width; fixture reason" };
  expect(
    "9. a NAMED-SCAR copy is RED with an empty baseline",
    false,
    scarFiles,
    empty,
    "NAMED-SCAR (COPY)",
    "ERR_BODY_CAP",
    "regardless of the ratchet baseline",
  );
  expect(
    "10. a NAMED-SCAR copy is STILL RED when the baseline records it",
    false,
    scarFiles,
    base({ "COPY ERR_BODY_CAP": [A_KT, B_KT] }),
    "NAMED-SCAR (COPY)",
    "ERR_BODY_CAP",
    "cannot be both baselined and strict",
  );
  // ...and the same tree with the name NOT on the list falls through to the ratchet, baselined
  // and green — the other half of "the list is what makes it strict".
  NAMED_SCARS = {};
  expect(
    "10b. the same copy, NOT on the list, is held by the baseline",
    true,
    scarFiles,
    base({ "COPY ERR_BODY_CAP": [A_KT, B_KT] }),
  );
  NAMED_SCARS = {};

  // ── the instrument's own guards ────────────────────────────────────────────────────────────
  expect(
    "11. a tree with no const at all refuses to pass vacuously",
    false,
    { "app/A.kt": EMPTY },
    empty,
    "refusing to pass vacuously",
  );
  expect(
    "12. a `const val` the parser cannot read is a parser/source disagreement",
    false,
    { "app/A.kt": DRIFT },
    empty,
    "the parser and the source disagree",
  );
  expect("13. a missing baseline cannot gate", false, { "app/A.kt": DUP_A }, null, "has no recorded census");

  // ── `--inventory` is the inventory, not the gate: it ignores the baseline entirely ─────────
  let result = run(inventory, dupFiles, empty);
  if (
    result.code !== 0 ||
    !result.text.includes("RATCHETED — 1 duplicated name(s)") ||
    !result.text.includes("SEAM_WIDTH")
  ) {
    failures.push(
      `\`--inventory\` must list an unbaselined COPY and still exit 0, got exit ${result.code}:\n${result.text}`,
    );
  }
  result = run(inventory, { "app/A.kt": EQUAL_COMMENT }, empty);
  if (result.code !== 1 || !result.text.includes("STRICT — 1 finding(s)")) {
    failures.push(`\`--inventory\` must exit 1 on a STRICT finding, got exit ${result.code}:\n${result.text}`);
  }

  // ── a NAMED_SCARS entry that describes nothing, and one with no reason ─────────────────────
  NAMED_SCARS = { NEVER_DUPLICATED: "a reason for a duplicate that does not exist" };
  expect(
    "14. a NAMED_SCARS entry naming no real duplicate is STALE",
    false,
    { "app/A.kt": BORING },
    empty,
    "NAMED-SCAR STALE",
    "NEVER_DUPLICATED",
  );
  NAMED_SCARS = { SEAM_WIDTH: "   " };
  expect(
    "15. a NAMED_SCARS entry with an empty reason is RED by name",
    false,
    dupFiles,
    empty,
    "NAMED-SCAR",
    "SEAM_WIDTH",
    "NO reason",
  );
  NAMED_SCARS = shipped;

  if (failures.length > 0) {
    process.stdout.write("const-single-source SELFTEST FAIL:\n");
    for (const failure of failures) {
      process.stdout.write("  " + failure.replace(/\n/g, "\n      ") + "\n");
    }
    return 1;
  }
  process.stdout.write(
    "const-single-source SELFTEST OK — STRICT: a must-stay-equal comment and a Knob-default " +
      "shadow are red with any baseline, an explanatory comment and a token-unrelated Knob twin " +
      "are green. RATCHET: an unbaselined COPY/COLLISION, a group that spread to a new file, a " +
      "stale entry and a partially-fixed entry are red; the same groups recorded in the baseline " +
      "are green. NAMED-SCAR outranks the baseline in both directions, and a listed name that " +
      "describes no real duplicate — or carries no reason — is red by name. `--inventory` lists the " +
      "held plane without gating it. An empty parse, a parser/source disagreement and a missing " +
      "baseline are untrustworthy rather than passing.\n",
  );
  return 0;
}

const USAGE = `usage: const-single-source [--ratchet | --inventory | --census | --record | --selftest] [--root <path>]
  --ratchet    gate leg: strict findings + growth/stale (this is what a bare run does)
  --inventory  the full inventory: strict findings + every held COPY/COLLISION
  --census     counts per class, no gating
  --record     print the ratchet baseline for this tree
  --selftest   red-green proof, out of tree
  --root       tree to measure (default: the repo root)
`;

function main(argv: string[]): number {
  let ratchetMode = false;
  let inventoryMode = false;
  let censusMode = false;
  let recordMode = false;
  let selftestMode = false;
  let rootArg: string | null = null;

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--ratchet") ratchetMode = true;
    else if (arg === "--inventory") inventoryMode = true;
    else if (arg === "--census") censusMode = true;
    else if (arg === "--record") recordMode = true;
    else if (arg === "--selftest") selftestMode = true;
    else if (arg === "--root") {
      i += 1;
      if (i >= argv.length) {
        process.stderr.write(USAGE);
        return 2;
      }
      rootArg = argv[i];
    } else {
      process.stderr.write(`const-single-source: unrecognised argument '${arg}'\n${USAGE}`);
      return 2;
    }
  }

  if (selftestMode) return selftest();

  const root = rootArg === null ? ROOT : realpathSync(rootArg);
  if (!existsSync(root)) {
    process.stderr.write(`const-single-source: ${root} does not exist\n`);
    return 2;
  }

  // A bare run is the GATE. The original ran the inventory here, which reads as a pass on a tree
  // whose ratchet plane has grown; that is the defect this port fixes.
  if (!ratchetMode && !inventoryMode && !censusMode && !recordMode) ratchetMode = true;

  if (recordMode) {
    process.stdout.write(pyJsonDumps(record(root)) + "\n");
    return 0;
  }
  if (censusMode) {
    census(root);
    return 0;
  }
  if (inventoryMode) {
    const { code, lines } = inventory(root);
    process.stdout.write(lines.join("\n") + "\n");
    return code;
  }
  const { code, lines } = ratchet(root);
  process.stdout.write(lines.join("\n") + "\n");
  return code;
}

process.exit(main(process.argv.slice(2)));
