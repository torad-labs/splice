#!/usr/bin/env bun
/** WALL for NF-02 — the shipped maxInflight default must not exceed the measured-good ceiling.
 *
 *  GAP (RED at authoring, 2026-07-26): Knob.kt:139 ships
 *      MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 100L)
 *  while splice's OWN committed measurement at config/splice.example.toml:202 reads
 *      "0.3% turn failure at inflight<=14, 11% at 38, 67% at 100".
 *  The shipped default IS the value measured as catastrophic. Observed live 2026-07-26 on
 *  claude-kimi-perf.jsonl: 92% of turns ran at inflight>=2, peaking at 32, 32% errors in the 14:00 hour.
 *
 *  The ceiling is READ FROM the example config's own measurement line, never hardcoded here — so
 *  re-measuring is the only sanctioned way to move this wall, and editing the wall cannot buy
 *  headroom (qgre zero_ratchet NO-SAVED-TRUTH).
 *
 *  EXIT 0 = gap closed (default <= ceiling).  EXIT 1 = gap open.
 *  --selftest = the POSITIVE CONTROL: proves this wall can distinguish an open gap from a closed one,
 *               so a do-nothing `exit(1)` cannot masquerade as enforcement (gate check C6).
 *
 *  V4-154: converted to TypeScript (bun). The load-bearing detail is the READER ASYMMETRY kept
 *  exactly: the knob is stripped and the example config is not.
 */
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

/** Python repr() of a string, and of a list of strings. An f-string that interpolates a LIST
 *  renders THIS -- `['a', 'b']` -- not JSON, so a port that used JSON.stringify produced a
 *  DIFFERENT failure message than the original on every red path while agreeing on every green
 *  one. Caught by driving the mutants as a CLI rather than feeding detect() a fixed corpus. */
/** Python escapes a character when str.isprintable() is False: categories Cc Cf Cs Co Cn Zl Zp,
 *  and Zs except the plain space. Only \n \r \t get short spellings; the rest render \xNN below
 *  0x100, \uNNNN below 0x10000, \UNNNNNNNN above. */
const NON_PRINTABLE = /[\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}\p{Zl}\p{Zp}\p{Zs}]/u;
function pyReprStr(s: string): string {
  const useDouble = s.includes("'") && !s.includes('"');
  const q = useDouble ? '"' : "'";
  let body = "";
  for (const ch of s) {
    const cp = ch.codePointAt(0) as number;
    if (ch === "\\") body += "\\\\";
    else if (ch === "\n") body += "\\n";
    else if (ch === "\r") body += "\\r";
    else if (ch === "\t") body += "\\t";
    else if (ch === q) body += "\\" + q;
    else if (NON_PRINTABLE.test(ch) && ch !== " ") {
      body +=
        cp < 0x100 ? "\\x" + cp.toString(16).padStart(2, "0")
        : cp < 0x10000 ? "\\u" + cp.toString(16).padStart(4, "0")
        : "\\U" + cp.toString(16).padStart(8, "0");
    } else body += ch;
  }
  return q + body + q;
}
function pyRepr(items: string[]): string {
  return "[" + items.map(pyReprStr).join(", ") + "]";
}

const ROOT = resolve(import.meta.dir, "../../../..");
const KNOB = resolve(ROOT, "core/src/main/kotlin/splice/core/config/Knob.kt");
const EXAMPLE = resolve(ROOT, "config/splice.example.toml");

const KNOB_RE = /MAX_INFLIGHT\(\s*"maxInflight"[\s\S]*?,\s*(\d+)L\s*\)/;
const MEASURE_RE = /([\d.]+)%\s+turn failure at inflight\s*<=\s*(\d+)/;
const FALLBACK_CEILING = 14;

/** Pure detection. Returns [problems, human summary]. No I/O — the selftest feeds it directly. */
export function detect(knobText: string | null, exampleText: string | null): [string[], string] {
  if (knobText === null) {
    return [["Knob.kt missing — refusing to pass vacuously"], "inconclusive"];
  }
  const m = knobText.match(KNOB_RE);
  if (m === null) {
    return [
      ["MAX_INFLIGHT knob declaration not found (shape changed?) — refusing to pass vacuously"],
      "inconclusive",
    ];
  }
  const def = parseInt(m[1], 10);

  let ceiling = FALLBACK_CEILING;
  let basis = "fallback (measurement line not found)";
  if (exampleText) {
    const mm = exampleText.match(MEASURE_RE);
    if (mm !== null) {
      ceiling = parseInt(mm[2], 10);
      basis = `config/splice.example.toml — ${mm[1]}% turn failure at inflight<=${ceiling}`;
    }
  }

  const summary = `maxInflight default = ${def}; measured-good ceiling = ${ceiling} (${basis})`;
  if (def > ceiling) {
    return [
      [
        `shipped default ${def} exceeds the measured-good ceiling ${ceiling}. ` +
          "Reconcile Knob.kt with splice's own measurement.",
      ],
      summary,
    ];
  }
  return [[], summary];
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  this wall after the real declaration is deleted. Same stripper cx_02/cx_09/cx_18 already carry.
 *
 *  Applied to the Knob.kt reader ONLY, and the asymmetry is the point. The knob default is a
 *  REQUIRED token — commenting the declaration out must not keep handing this wall a number to
 *  approve, and the shape guard must fire instead. The example-config reader stays RAW because the
 *  measurement it parses IS a comment BY DESIGN (`# ...0.3% turn failure at inflight<=14...`);
 *  stripping there would not harden anything, it would blind the wall to its own ceiling and drop
 *  it onto FALLBACK_CEILING — a weaker check whenever the committed measurement is stricter. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? readFileSync(p, "utf8") : null;
}

function readCode(p: string): string | null {
  return codeOnly(read(p));
}

export const OPEN_KNOB =
  'MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 100L),';
export const CLOSED_KNOB =
  'MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 12L),';
export const EXAMPLE_FIXTURE =
  "# Measured on this box: 0.3% turn failure at inflight<=14, 11% at 38, 67% at 100.\n";

function selftest(): number {
  const fails: string[] = [];
  const [openP] = detect(OPEN_KNOB, EXAMPLE_FIXTURE);
  if (openP.length === 0) {
    fails.push("open-gap fixture (default 100, ceiling 14) must be RED");
  }
  const [closedP] = detect(CLOSED_KNOB, EXAMPLE_FIXTURE);
  if (closedP.length > 0) {
    fails.push(`closed-gap fixture (default 12, ceiling 14) must be GREEN, got ${pyRepr(closedP)}`);
  }
  // the ceiling must come from the measurement, not a literal: same knob, stricter measurement -> red
  const [strictP] = detect(CLOSED_KNOB, "0.3% turn failure at inflight<=8, 67% at 100\n");
  if (strictP.length === 0) {
    fails.push("ceiling must derive from the measurement line (default 12 vs ceiling 8 should be RED)");
  }
  const [missingP] = detect(null, EXAMPLE_FIXTURE);
  if (missingP.length === 0) {
    fails.push("a missing Knob.kt must be RED, never a vacuous pass");
  }
  const [shapeP] = detect("val SOMETHING_ELSE = 1", EXAMPLE_FIXTURE);
  if (shapeP.length === 0) {
    fails.push("an unrecognised knob shape must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("NF-02 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "NF-02 SELFTEST OK — red on open gap, green on closed gap, ceiling tracks the measurement, " +
      "inconclusive inputs stay red\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const [problems, summary] = detect(readCode(KNOB), read(EXAMPLE));
  process.stdout.write(`NF-02: ${summary}\n`);
  if (problems.length > 0) {
    process.stdout.write("NF-02 WALL RED:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write("NF-02 WALL GREEN: shipped default is within the measured-good ceiling.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
