#!/usr/bin/env bun
/**
 * V4-88 — a numeric constant carries the reason it is that number. RATCHET.
 *
 * WHY THIS EXISTS (ARCH-AUDIT 2026-09-17, class (d)). Naming a magic number is half the job. This
 * tree does the naming well — 619 numeric `const val` declarations in main sources, almost all with
 * a readable name — and then leaves the number itself unexplained:
 *
 *     private const val HTTP_TOO_MANY = 429            // fine: the name IS the reason
 *     private const val MAX_TEXT_BYTES = 65_536        // why 64 KiB? nobody wrote it down
 *     private const val PROACTIVE_WINDOW_MS = 300_000L // why five minutes? nobody wrote it down
 *
 * The second and third are where a value silently rots. The number was chosen against something —
 * a provider's observed limit, a surveyed harness floor, a herd-starvation window — and once that
 * reasoning is gone, the next session either leaves a wrong number alone because it looks
 * deliberate, or changes a right one because it looks arbitrary. The tree already knows how to do
 * this properly; Knob.UPSTREAM_RETRIES carries "4 attempts matches the surveyed harness floor
 * (codex 4, gemini/Claude Code higher); the old default of 2 with ~200ms total backoff still failed
 * turns on 2-3s blips (G4b)". That is the standard. 491 declarations do not meet it.
 *
 * A wall that failed the build on all 491 would be reverted by lunchtime, so this is a RATCHET: the
 * census is recorded, the gate fails on GROWTH, and the number is meant to fall. It is a debt meter
 * with a one-way valve, not a style rule.
 *
 * DENOMINATOR, FROM THE SOURCE (§24), never a hand list. Every `const val` under
 * gateway/*\/src/main/**\/*.kt is parsed off disk; the ones whose value is a numeric literal (or an
 * arithmetic expression over numeric literals — `64 * 1024`, `8L * 24 * 60 * 60 * 1000`) are the
 * denominator. 1184 declarations today, 619 numeric. A file added tomorrow is in scope with no edit
 * to this file. Three guards refuse a vacuous pass: zero source files is a failure, a parsed count
 * that disagrees with the count of `const val` lines in the source is a failure, and a baseline
 * whose own recorded denominator no longer matches the measured one is reported as drifted.
 *
 * WHAT COUNTS AS A REASON, and why it is not a comment-length check. The comment must be ADJACENT —
 * the contiguous `//` or KDoc block immediately above the declaration, or a trailing `//` on the
 * same line. Contiguity matters: a blank line between a comment and a declaration means the comment
 * belongs to whatever is above it, and crediting it here would let one explained constant launder
 * the six unexplained ones beneath it. Then either:
 *   - it opens with `why:` — the explicit spelling, for a number whose reason is short; or
 *   - it holds at least MIN_REASON_WORDS words — a sentence. Measured on this tree the distribution
 *     is bimodal (489 declarations with NO adjacent comment at all, 109 with twelve words or more,
 *     almost nothing between), so the threshold is not a knife-edge: moving it from 2 to 6 moves the
 *     census by 4 declarations out of 619.
 * A name is not a reason. `HTTP_TOO_MANY = 429` is perfectly clear and still counts as silent,
 * because the ratchet's job is to make the tree's explained fraction rise, and exempting
 * "self-evident" names means hand-judging 619 constants — the hand list this wall exists to avoid.
 * The cost is that some entries in the baseline are cheap to retire; that is the intended shape.
 *
 * THE RATCHET, both directions (mirroring checks/concentration.ts's two arms):
 *   GROWTH — the measured total above the baseline total, or any FILE above its baseline count, is
 *            a regression and fails BY NAME. A new file with silent constants fails by name too.
 *   STALE  — a baseline entry held ABOVE its file's measured count, or naming a file that no longer
 *            exists or no longer has silent constants, fails. A baseline above the measured count is
 *            unearned room for the next regression to hide in, so retiring a constant means lowering
 *            the baseline in the same commit. That is the point of the valve.
 *
 * WHAT IS NOT CAUGHT, stated rather than implied.
 *   An INLINE numeric literal (`if (status == 429)`, `delay(250)`). The subject here is the
 *   declaration that names a number; a literal with no name is a different wall, and this tree's
 *   detekt config already carries MagicNumber.
 *   A reason that is WRONG, or one that restates the name in four words ("the max text bytes cap").
 *   A word count cannot read. What it can do is make the absence of any attempt mechanical, and put
 *   the pressure of a falling baseline behind it.
 *   Non-numeric constants — strings, booleans, chars. A wire word documents itself; a magnitude does
 *   not.
 *   Test and testFixtures sources. Main sources are the subject, per the row.
 *
 * SELFTEST. `--selftest` proves BOTH directions out of tree: GREEN on a compliant fixture (a `why:`
 * one-liner, a KDoc sentence, a trailing comment) and on the BORING cases (a tree with one numeric
 * const and a baseline of 1; a tree with no numeric consts and a baseline of 0 — both green WITH
 * their count); RED BY NAME on a synthetic silent constant added to a temp copy, on a new file
 * carrying one, on a baseline entry held above the measured count, on a baseline naming a vanished
 * file, on a comment separated from its declaration by a blank line, on a parse yielding zero
 * declarations, and on a parsed count that disagrees with the source.
 *
 * THE DENOMINATOR GLOB IS VERIFIED, NOT ASSUMED. Python's pathlib `**` and bun's Glob are different
 * implementations of the same pattern language, and this file's whole denominator comes from one of
 * them, so a difference would move the census silently. Measured on this tree before the port:
 * `gateway/*\/src/main/**\/*.kt` yields 655 files under pathlib and 655 under Bun.Glob, identical
 * sets. Re-measure that if the pattern is ever changed.
 *
 * LINE SPLITTING IS PYTHON'S, NOT JAVASCRIPT'S. `str.splitlines()` does not produce a trailing empty
 * element for a trailing newline and breaks on several separators `"\n"` does not, and both
 * properties are load-bearing here: the parser/source disagreement guard compares a per-LINE count,
 * so an off-by-one would report a disagreement on every file in the tree.
 *
 * Usage:
 *     bun checks/silent-constants.ts --ratchet          # gate leg: fail on growth or a stale entry
 *     bun checks/silent-constants.ts --top N            # print the worst N files and their constants
 *     bun checks/silent-constants.ts --record           # print the baseline JSON for this tree
 *     bun checks/silent-constants.ts --selftest         # red-green proof, out of tree
 *     bun checks/silent-constants.ts --root DIR         # tree to measure (default: the repo root)
 *
 * A BARE RUN PRINTS HELP AND EXITS 2, which is the mode discipline this repo now requires and which
 * this file already had: there is NO non-gating default, so a mis-invocation cannot read as a pass.
 * `--top` and `--record` are view modes that exit 0, and they are the permitted shape — both sit
 * behind explicit flags the gate never uses.
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const MAIN_GLOB = "gateway/*/src/main/**/*.kt";
const BASELINE_REL = "checks/config/silent-constants-baseline.json";

// A sentence. See WHAT COUNTS AS A REASON for the measured insensitivity of this threshold.
const MIN_REASON_WORDS = 4;

const DECL = /^[ \t]*(?:(?:public|internal|private|protected)\s+)?const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[^=]+?)?\s*=[ \t]*(.*)$/;
const CONST_VAL_LINE = /\bconst\s+val\b/;
const NUM_TOKEN = /^(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*(?:\.[0-9_]+)?(?:[eE][-+]?[0-9]+)?)[LlFfDdUu]*$/;
const ARITH_SPLIT = /\s*(?:\*|\/|\+|-|%|shl|shr|and|or|\(|\))\s*/g;
const WORD = /[A-Za-z]{2,}/g;
const WHY_MARKER = /^\s*why:/i;

const USAGE_LINES = [
  "usage: silent-constants.ts [-h] [--ratchet] [--top TOP] [--record]",
  "                           [--selftest] [--root ROOT]",
  "",
  "numeric constants carry their reason (ratchet)",
  "",
  "options:",
  "  -h, --help   show this help message and exit",
  "  --ratchet    gate leg: fail on growth or a stale entry",
  "  --top TOP    print the worst N files and their constants",
  "  --record     print the baseline JSON for this tree",
  "  --selftest   red-green proof, out of tree",
  "  --root ROOT  tree to measure (default: the repo root)",
];

/** Python's `str.splitlines()`: no trailing empty element, and several separators beyond `\n`. */
const LINE_BOUNDARY = /\r\n|[\n\r\v\f\x1c-\x1e\x85\u2028\u2029]/;

function pySplitlines(text: string): string[] {
  if (text === "") return [];
  const parts = text.split(LINE_BOUNDARY);
  if (LINE_BOUNDARY.test(text.slice(-2)) || /\r\n$/.test(text)) parts.pop();
  return parts;
}

interface Silent {
  name: string;
  rel: string;
  line: number;
  value: string;
}

const where = (s: Silent): string => `${s.rel}:${s.line}`;

/** (code, comment) — split at a `//` that is not inside a string literal. */
function stripLineComment(text: string): [string, string] {
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
    if (ch === "/" && i + 1 < text.length && text[i + 1] === "/") return [text.slice(0, i), text.slice(i + 2)];
    i += 1;
  }
  return [text, ""];
}

/** A numeric literal, or arithmetic over numeric literals. Strings/booleans/chars are out. */
function isNumeric(raw: string): boolean {
  let code = stripLineComment(raw)[0];
  code = code.trim().replace(/,+$/, "");
  if (!code) return false;
  const parts = code.split(ARITH_SPLIT).filter((p) => p.trim() !== "");
  return parts.length > 0 && parts.every((p) => NUM_TOKEN.test(p));
}

/** The CONTIGUOUS comment block above lines[index], plus its own trailing comment. */
function adjacentReason(lines: string[], index: number): string {
  const parts: string[] = [];
  let j = index - 1;
  while (j >= 0) {
    const stripped = lines[j].trim();
    if (
      stripped.startsWith("//") ||
      stripped.startsWith("*") ||
      stripped.startsWith("/*") ||
      stripped.endsWith("*/")
    ) {
      // `g`: python's re.sub replaces EVERY occurrence; a non-global replace here would strip only
      // the first marker and quietly leave `// //` in the reason text.
      parts.push(stripped.replace(/^\/\*+|^\*+\/?|^\/\/|\*\/$/g, ""));
      j -= 1;
      continue;
    }
    break;
  }
  parts.reverse();
  const trailing = stripLineComment(lines[index])[1];
  if (trailing) parts.push(trailing);
  return parts.map((p) => p.trim()).join(" ").trim();
}

function hasReason(comment: string): boolean {
  return (
    comment !== "" && (WHY_MARKER.test(comment) || (comment.match(WORD) ?? []).length >= MIN_REASON_WORDS)
  );
}

/** (silent declarations, numeric denominator, problems that make the run untrustworthy). */
function scan(root: string): { silent: Silent[]; numeric: number; problems: string[] } {
  const problems: string[] = [];
  // followSymlinks IS LOAD-BEARING, and the real-tree differential could not have caught its
  // absence: python's pathlib.glob descends through a symlinked directory and Bun.Glob does NOT by
  // default. On the tree as committed there is no symlink under gateway/*/ so both implementations
  // agree — 655 files, identical sets — and the difference is invisible. It surfaces only in the
  // selftest's harness, which materialises ONE module and symlinks the rest so a fixture can mutate
  // a real file: without this flag the census there reads 5 silent constants instead of 415, and the
  // oracle goes red with a STALE report about files that are present. Measured: 103 files through a
  // symlinked module with the flag, 0 without, 103 under pathlib.
  const files = [...new Bun.Glob(MAIN_GLOB).scanSync({ cwd: root, followSymlinks: true })].sort();
  if (files.length === 0) {
    return { silent: [], numeric: 0, problems: [`no main sources matched ${MAIN_GLOB} under ${root} — the denominator is absent`] };
  }
  const silent: Silent[] = [];
  let numeric = 0;
  let parsed = 0;
  let rawTotal = 0;
  for (const rel of files) {
    const lines = pySplitlines(readFileSync(join(root, rel), "utf8"));
    lines.forEach((line, i) => {
      const stripped = line.trim();
      if (!stripped.startsWith("//") && !stripped.startsWith("*") && CONST_VAL_LINE.test(line)) rawTotal += 1;
      const match = DECL.exec(line);
      if (match === null) return;
      parsed += 1;
      let value = match[2].trim();
      if (!value) value = i + 1 < lines.length ? lines[i + 1].trim() : "";
      if (!isNumeric(value)) return;
      numeric += 1;
      if (!hasReason(adjacentReason(lines, i))) {
        silent.push({ name: match[1], rel, line: i + 1, value: stripLineComment(value)[0].trim() });
      }
    });
  }
  if (parsed === 0) {
    problems.push(
      `parsed 0 const declarations from ${files.length} main source file(s) — refusing to pass ` +
        "vacuously, because a green over an empty denominator is what this ratchet exists to prevent",
    );
  }
  if (rawTotal !== parsed) {
    problems.push(
      `parsed ${parsed} declarations but the tree holds ${rawTotal} \`const val\` lines — the ` +
        "parser and the source disagree, so no census from this run can be trusted",
    );
  }
  return { silent, numeric, problems };
}

interface Baseline {
  recorded: string;
  denominator: number;
  total: number;
  files: Record<string, number>;
}

function loadBaseline(root: string): { data: Baseline | null; problems: string[] } {
  const path = join(root, BASELINE_REL);
  if (!existsSync(path)) {
    return { data: null, problems: [`${BASELINE_REL}: missing — the ratchet has no recorded census to hold the tree to`] };
  }
  let data: Record<string, unknown>;
  try {
    data = JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
  } catch (exc) {
    return { data: null, problems: [`${BASELINE_REL}: unreadable (${exc}) — a ratchet that cannot read its baseline cannot gate`] };
  }
  for (const key of ["recorded", "total", "denominator", "files"]) {
    if (!(key in data)) return { data: null, problems: [`${BASELINE_REL}: missing required key '${key}'`] };
  }
  return { data: data as unknown as Baseline, problems: [] };
}

/** (exit code, report lines). Prints the baseline, the measurement, and both arms, always. */
function ratchet(root: string): { code: number; lines: string[] } {
  const out: string[] = [];
  const { silent, numeric, problems: scanProblems } = scan(root);
  const { data: baseline, problems: baselineProblems } = loadBaseline(root);
  const problems = [...scanProblems, ...baselineProblems];
  if (problems.length > 0) {
    out.push("FAIL: silent-constants ratchet — the instrument is untrustworthy:");
    out.push(...problems.map((p) => `  ✗ ${p}`));
    return { code: 2, lines: out };
  }
  if (baseline === null) return { code: 2, lines: out };

  const measured: Record<string, number> = {};
  for (const item of silent) measured[item.rel] = (measured[item.rel] ?? 0) + 1;
  const recorded: Record<string, number> = { ...baseline.files };

  out.push(
    `SILENT-CONSTANTS RATCHET — baseline recorded ${baseline.recorded}, ` +
      `reason = \`// why:\` or a sentence of ${MIN_REASON_WORDS}+ words, adjacent`,
  );
  out.push(`  ${"numeric const val".padEnd(24)} denominator ${String(baseline.denominator).padStart(4)}   measured ${String(numeric).padStart(4)}`);
  out.push(`  ${"silent (no reason)".padEnd(24)} baseline    ${String(baseline.total).padStart(4)}   measured ${String(silent.length).padStart(4)}   [GATED]`);
  out.push(`  ${"files carrying them".padEnd(24)} baseline    ${String(Object.keys(recorded).length).padStart(4)}   measured ${String(Object.keys(measured).length).padStart(4)}`);

  const failures: string[] = [];

  if (numeric !== baseline.denominator) {
    out.push(
      `  NOTE: the numeric denominator moved ${baseline.denominator} -> ${numeric}; the gate ` +
        "reads the silent COUNT, not the ratio, so this is reported and not gated",
    );
  }

  const growth = Object.keys(measured)
    .filter((rel) => measured[rel] > (recorded[rel] ?? 0))
    .sort();
  for (const rel of growth) {
    const was = recorded[rel] ?? 0;
    const names = silent
      .filter((item) => item.rel === rel)
      .map((item) => `${item.name} = ${item.value} (${where(item)})`)
      .join(", ");
    failures.push(
      `GROWTH: ${rel} carries ${measured[rel]} silent numeric const(s), baseline ${was} — ${names}. ` +
        "Give the new one an adjacent reason (`// why: ...` or a sentence), or lower another " +
        `entry in ${BASELINE_REL} in the same commit`,
    );
  }

  for (const rel of Object.keys(recorded).sort()) {
    const now = measured[rel] ?? 0;
    if (now < recorded[rel]) {
      failures.push(
        `STALE: ${BASELINE_REL} claims ${recorded[rel]} silent const(s) in ${rel} but only ${now} ` +
          "remain — lower the entry to the measured count. A baseline held above the " +
          "measurement is unearned room for the next regression to hide in",
      );
    }
    if (!existsSync(join(root, rel))) {
      failures.push(`STALE: ${BASELINE_REL} names ${rel}, which no longer exists — delete the entry`);
    }
  }

  if (silent.length > baseline.total) {
    failures.push(`GROWTH: the tree total rose ${baseline.total} -> ${silent.length}`);
  } else if (silent.length < baseline.total) {
    failures.push(
      `STALE: the tree total fell ${baseline.total} -> ${silent.length}, and ` +
        `${BASELINE_REL} still claims ${baseline.total} — record the win by lowering it`,
    );
  }

  if (failures.length > 0) {
    out.push("");
    out.push(`FAIL: silent-constants ratchet — ${failures.length} problem(s):`);
    out.push(...failures.map((f) => `  ✗ ${f}`));
    return { code: 1, lines: out };
  }

  out.push("");
  out.push(
    `OK: silent-constants ratchet holds — ${silent.length} silent numeric const(s) across ` +
      `${Object.keys(measured).length} file(s), exactly the ${baseline.recorded} baseline`,
  );
  return { code: 0, lines: out };
}

/** The baseline document for the tree as it stands. Used to author the JSON, never by the gate. */
function record(root: string): Baseline {
  const { silent, numeric, problems } = scan(root);
  if (problems.length > 0) throw new Error(problems.join("; "));
  const files: Record<string, number> = {};
  for (const item of silent) files[item.rel] = (files[item.rel] ?? 0) + 1;
  const sortedFiles: Record<string, number> = {};
  for (const k of Object.keys(files).sort()) sortedFiles[k] = files[k];
  return {
    _note: undefined as never,
    ...({
      _note:
        "AUTHORED BY checks/silent-constants.ts --record, MEASURED never estimated. Every entry " +
        "is a numeric `const val` in main sources with no adjacent reason comment (see that " +
        "file's WHAT COUNTS AS A REASON). The gate fails on GROWTH and on a STALE entry held " +
        "above the measurement, so lowering an entry is how a fix is recorded. This file is a " +
        "debt inventory with a one-way valve; it is meant to shrink to nothing.",
      recorded: "2026-09-17",
      denominator: numeric,
      total: silent.length,
      files: sortedFiles,
    } as Baseline),
  };
}

function top(root: string, count: number): void {
  const { silent, numeric, problems } = scan(root);
  for (const problem of problems) process.stdout.write(`  UNTRUSTED: ${problem}\n`);
  const perFile: Record<string, number> = {};
  for (const item of silent) perFile[item.rel] = (perFile[item.rel] ?? 0) + 1;
  process.stdout.write(
    `silent-constants: ${silent.length} of ${numeric} numeric const val carry no adjacent reason\n`,
  );
  const ranked = Object.entries(perFile)
    .sort((a, b) => (b[1] - a[1]) || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
    .slice(0, count);
  for (const [rel, n] of ranked) {
    process.stdout.write(`  ${String(n).padStart(3)}  ${rel}\n`);
    for (const item of silent) {
      if (item.rel === rel) process.stdout.write(`         ${String(item.line).padStart(4)}  ${item.name} = ${item.value}\n`);
    }
  }
}

// ── selftest ──────────────────────────────────────────────────────────────────────────────────

const COMPLIANT = `package splice.a

// why: the provider's observed ceiling
private const val MAX_TEXT_BYTES = 65_536

/** Five minutes is one client-retry cycle, so a recovered account resumes without operator help. */
private const val PROACTIVE_WINDOW_MS = 300_000L

private const val CHUNK = 64 * 1024 // 64 KiB matches the transport's own buffer, measured 2026-09
`;

const ONE_SILENT = "package splice.a\n\nprivate const val LONELY = 7\n";

const NO_NUMERIC = `package splice.a

// a wire word documents itself
private const val FIELD_ROLE = "role"
`;

const TWO_SILENT = "package splice.a\n\nprivate const val ONE = 7\n\nprivate const val TWO = 9\n";

// A comment separated from its declaration by a blank line belongs to whatever is above it.
const DETACHED = `package splice.a

// why: this explains the constant above, not the one below
internal fun f(): Int = 1

private const val ORPHANED = 41
`;

const EMPTY = "package splice.a\n\ninternal fun f(): Int = 7\n";

const DRIFT = `package splice.a

// why: readable
private const val OK = 1

@Suppress("MagicNumber") private const val ANNOTATED = 3
`;

function writeTree(root: string, files: Record<string, string>, baseline: Baseline | null): void {
  const gateway = join(root, "gateway");
  if (existsSync(gateway)) rmSync(gateway, { recursive: true, force: true });
  for (const [rel, body] of Object.entries(files)) {
    const idx = rel.indexOf("/");
    const module = rel.slice(0, idx);
    const name = rel.slice(idx + 1);
    const target = join(root, "gateway", module, "src/main/kotlin/splice", name);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, body, "utf8");
  }
  const path = join(root, BASELINE_REL);
  mkdirSync(dirname(path), { recursive: true });
  if (baseline === null) {
    if (existsSync(path)) rmSync(path);
    return;
  }
  writeFileSync(path, JSON.stringify(baseline, null, 2), "utf8");
}

function base(total: number, denominator: number, files: Record<string, number>): Baseline {
  return { recorded: "selftest", total, denominator, files };
}

function selftest(): number {
  const failures: string[] = [];

  const run = (files: Record<string, string>, baseline: Baseline | null): { code: number; text: string } => {
    const root = mkdtemp();
    try {
      writeTree(root, files, baseline);
      const { code, lines } = ratchet(root);
      return { code, text: lines.join("\n") };
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  };

  const expectGreen = (label: string, files: Record<string, string>, baseline: Baseline | null, ...needles: string[]): void => {
    const { code, text } = run(files, baseline);
    if (code !== 0) failures.push(`${label} must be GREEN, got exit ${code}:\n${text}`);
    for (const needle of needles) {
      if (!text.includes(needle)) failures.push(`${label} must report '${needle}', got:\n${text}`);
    }
  };

  const expectRed = (label: string, files: Record<string, string>, baseline: Baseline | null, ...needles: string[]): void => {
    const { code, text } = run(files, baseline);
    if (code === 0) failures.push(`${label} must be RED, got exit 0:\n${text}`);
    for (const needle of needles) {
      if (!text.includes(needle)) failures.push(`${label} must be RED naming '${needle}', got:\n${text}`);
    }
  };

  const A = "gateway/app/src/main/kotlin/splice/A.kt";

  expectGreen("the compliant fixture (why:, a KDoc sentence, a trailing sentence)", { "app/A.kt": COMPLIANT }, base(0, 3, {}), "measured    0");
  expectGreen("the BORING case: one silent const, baseline 1", { "app/A.kt": ONE_SILENT }, base(1, 1, { [A]: 1 }), "measured    1");
  expectGreen("the BORING case: no numeric const at all, baseline 0", { "app/A.kt": NO_NUMERIC }, base(0, 0, {}), "denominator    0   measured    0");

  expectRed("a synthetic silent const added to an explained file", { "app/A.kt": COMPLIANT + "\nprivate const val SNEAKED = 13\n" }, base(0, 3, {}), "GROWTH", "SNEAKED");
  expectRed("a NEW file carrying a silent const", { "app/A.kt": COMPLIANT, "core/B.kt": ONE_SILENT }, base(0, 3, {}), "GROWTH", "LONELY");
  expectRed("a baseline entry held ABOVE the measurement", { "app/A.kt": ONE_SILENT }, base(2, 1, { [A]: 2 }), "STALE", "only 1");
  expectRed(
    "a baseline naming a file that no longer exists",
    { "app/A.kt": ONE_SILENT },
    base(1, 1, { [A]: 1, "gateway/core/src/main/kotlin/splice/Gone.kt": 0 }),
    "STALE",
    "no longer exists",
  );
  expectRed("a comment detached from its declaration by a blank line is not a reason", { "app/A.kt": DETACHED }, base(0, 1, {}), "GROWTH", "ORPHANED");
  expectRed("the tree total falling without the baseline following it", { "app/A.kt": ONE_SILENT }, base(2, 2, { [A]: 1 }), "STALE", "fell 2 -> 1");
  expectRed("a tree with no const at all refuses to pass vacuously", { "app/A.kt": EMPTY }, base(0, 0, {}), "refusing to pass vacuously");
  expectRed("a `const val` the parser cannot read is a parser/source disagreement", { "app/A.kt": DRIFT }, base(0, 1, {}), "the parser and the source disagree");
  expectRed("a missing baseline cannot gate", { "app/A.kt": ONE_SILENT }, null, "has no recorded census");
  // The two-item control: the ratchet must count, not merely detect. A file with two silent
  // constants and a baseline of one is GROWTH even though the file was already on the list.
  expectRed("a file already on the list gaining a second silent const", { "app/A.kt": TWO_SILENT }, base(1, 2, { [A]: 1 }), "GROWTH", "carries 2 silent");

  if (failures.length > 0) {
    process.stdout.write("silent-constants SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure.replace(/\n/g, "\n      ") + "\n");
    return 1;
  }
  process.stdout.write(
    "silent-constants SELFTEST OK — `why:`, a KDoc sentence and a trailing sentence are " +
      "green; one silent const against a baseline of one is green WITH its count, and so is a " +
      "tree with no numeric const; a synthetic silent const, a new file carrying one, a second " +
      "one in a file already listed, a detached comment, a baseline held above the measurement, " +
      "a baseline naming a vanished file, a total that fell, an empty parse, a parser/source " +
      "disagreement and a missing baseline are all red by name\n",
  );
  return 0;
}

function mkdtemp(): string {
  const dir = join(tmpdir(), `silent-constants-${process.pid}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(dir, { recursive: true });
  return dir;
}

function main(argv: string[]): number {
  const flags = { ratchet: false, record: false, selftest: false, top: 0, root: null as string | null };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === "--ratchet") flags.ratchet = true;
    else if (a === "--record") flags.record = true;
    else if (a === "--selftest") flags.selftest = true;
    else if (a === "--top") {
      const n = Number(argv[i + 1]);
      if (!Number.isInteger(n)) return usage();
      flags.top = n;
      i += 1;
    } else if (a === "--root") {
      if (i + 1 >= argv.length) return usage();
      flags.root = argv[i + 1];
      i += 1;
    } else {
      return usage();
    }
  }

  if (flags.selftest) return selftest();

  const root = flags.root ? resolve(flags.root) : ROOT;
  if (!existsSync(root)) {
    process.stderr.write(`silent-constants: ${root} does not exist\n`);
    return 2;
  }

  if (flags.record) {
    process.stdout.write(JSON.stringify(record(root), null, 2) + "\n");
    return 0;
  }
  if (flags.top) {
    top(root, flags.top);
    return 0;
  }
  if (flags.ratchet) {
    const { code, lines } = ratchet(root);
    process.stdout.write(lines.join("\n") + "\n");
    return code;
  }
  return usage();
}

function usage(): number {
  process.stdout.write(USAGE_LINES.join("\n") + "\n");
  return 2;
}

process.exit(main(process.argv.slice(2)));
