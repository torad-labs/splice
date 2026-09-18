#!/usr/bin/env bun
/** WALL for JW-05 — doctor must read what actually HAPPENED, not just what is configured.
 *
 *  GAP (RED at authoring, 2026-08-07): every doctor section is static (binaries, symlinks, TOML,
 *  credential presence, /health version). A fully-configured install whose head has been 429-cooled
 *  for an hour or whose last twenty turns died upstream still prints "Everything checks out." —
 *  while both runtime instruments (HeadHealthCounters on /api/heads, the per-head perf JSONL
 *  outcome field) already exist and persist.
 *
 *  GREEN requires ALL of:
 *    1. doctor has a runtime section (INFO-skipped when the daemon is stopped);
 *    2. it reads the per-head health counters from /api/heads (providerErrors split);
 *    3. it reads the perf JSONL outcome tail (recency framing, not lifetime totals alone);
 *    4. warnings carry the actionable fix (splice logs --head ...).
 *
 *  EXIT 0 = runtime visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Two Python idioms were carried exactly rather than
 *  approximated: `(_read(p) or "")` is FALSY-or, so an all-comment file collapsing to the empty
 *  string behaves like a missing one (a JS `??` would not), and main()'s conditional expression
 *  binds the whole concatenation, not just the last operand.
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
const DOCTOR = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt");
// The runtime section lives in its own file (DoctorCommand.kt sits at detekt's file function
// budget); the wall reads the whole doctor surface so a legitimate split cannot read as a gap.
const RUNTIME = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/cli/DoctorRuntime.kt");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(doctor: string | null): string[] {
  if (doctor === null) {
    return ["DoctorCommand.kt missing — refusing to pass vacuously"];
  }
  if (!doctor.includes("daemonChecks")) {
    return ["doctor section table not found (shape changed?) — refusing to pass vacuously"];
  }
  const problems: string[] = [];
  if (!doctor.includes('"runtime"')) {
    problems.push("no runtime section — doctor blesses an install whose turns are dying");
  }
  if (!doctor.includes("providerErrors")) {
    problems.push(
      "the per-head health counters are never read — the provider-vs-local " +
        "split exists precisely for this diagnosis",
    );
  }
  if (!doctor.includes("perf") && !doctor.includes("outcome")) {
    problems.push(
      "the perf JSONL outcome tail is never read — 'last failure: Nm ago' is " +
        "the answer doctor exists to give",
    );
  }
  if (!doctor.includes("splice logs --head")) {
    problems.push("runtime warnings carry no actionable fix");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  a REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *
 *  Both readers strip because every check here is a REQUIRED token; this wall asserts no BANNED
 *  string, which is the one direction that must stay raw (the jw_08 split) so a violation cannot
 *  hide inside a comment. This surface is unusually comment-dense — DoctorRuntime.kt's own header
 *  narrates the `perf` JSONL `outcome` tail in prose — so before this, the perf/outcome check was
 *  already satisfied by the file's description of a section that could have been deleted. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const OPEN_FIX = "daemonChecks static only";
export const CLOSED_FIX =
  'daemonChecks\n"runtime" to guarded { runtimeChecks }\nproviderErrors\n' +
  'perf outcome tail\nfix = "splice logs --head x --tail 50"';

/** DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
 *  can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
 *  sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
 *  turn detect red, or that token has rotted into always-green furniture. */
export function derivedMutants(): string[] {
  const live = (read(DOCTOR) || "") + "\n" + (read(RUNTIME) || "");
  if (detect(live).length > 0) {
    return ["derived mutants need the live tree green; the wall is RED right now"];
  }
  const fails: string[] = [];
  for (const tokens of [['"runtime"'], ["providerErrors"], ["perf", "outcome"], ["splice logs --head"]]) {
    let mutant = live;
    for (const t of tokens) {
      mutant = mutant.replaceAll(t, "");
    }
    if (detect(mutant).length === 0) {
      fails.push(`live tree with ${tokens.join("/")} deleted must be RED — furniture token`);
    }
  }
  return fails;
}

function selftest(): number {
  const fails: string[] = [];
  if (detect(OPEN_FIX).length === 0) {
    fails.push("static-only doctor must be RED");
  }
  if (detect(CLOSED_FIX).length > 0) {
    fails.push(`runtime-reading doctor must be GREEN, got ${pyRepr(detect(CLOSED_FIX))}`);
  }
  if (detect(CLOSED_FIX.replaceAll("providerErrors\n", "")).length === 0) {
    fails.push("a runtime section that skips the counters must be RED");
  }
  if (detect(CLOSED_FIX.replaceAll('fix = "splice logs --head x --tail 50"', "")).length === 0) {
    fails.push("warnings without the fix must be RED");
  }
  if (detect(null).length === 0) {
    fails.push("a missing DoctorCommand.kt must be RED, never a vacuous pass");
  }
  fails.push(...derivedMutants());
  if (fails.length > 0) {
    process.stdout.write("JW-05 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "JW-05 SELFTEST OK — red on static-only, counter-blind, fix-less shapes and missing " +
      "file, and on the LIVE tree with each required token deleted; green only when doctor " +
      "reads the runtime instruments\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const doctor = read(DOCTOR);
  const problems = detect(doctor ? (doctor || "") + "\n" + (read(RUNTIME) || "") : null);
  if (problems.length > 0) {
    process.stdout.write("JW-05 WALL RED — doctor is a static-config checker only:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "JW-05 WALL GREEN: doctor reads the health counters and the perf outcome tail, with fixes.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
