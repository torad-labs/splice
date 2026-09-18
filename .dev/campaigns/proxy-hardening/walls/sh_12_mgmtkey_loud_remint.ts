#!/usr/bin/env bun
/** WALL for SH-12 — MgmtKey may mint quietly only on first run; an unreadable/blank EXISTING
 *  file must mint LOUDLY and record when.
 *
 *  GAP (RED at authoring, 2026-08-07): ensure() collapses "no key file yet" and "key file exists
 *  but unreadable" into one silent fallthrough (runCatching...discard) and mints fresh bytes —
 *  silently revoking the dashboard session, every script's bearer, and the launch shim's
 *  stale-daemon stop hook. The operator's symptom is an unexplained 401 everywhere.
 *
 *  GREEN requires ALL of:
 *    1. the silent discard fallthrough is gone;
 *    2. the unreadable/blank-but-present case logs a loud line naming the consequence ("every
 *       existing bearer" invalid) — the absent-file first-run mint stays quiet;
 *    3. mintedAtMs is recorded so doctor/status can flag a suspiciously fresh key (JW wiring).
 *
 *  EXIT 0 = loud remint. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). The load-bearing shape is TWO VIEWS OF ONE FILE — the
 *  requirements read the comment-stripped text and the BAN reads the raw text — so a remint that
 *  survives only as a comment fails, AND a banned fallthrough commented out still fails. Both
 *  halves are in the corpus.
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
const KEY = resolve(ROOT, "gateway/core/src/main/kotlin/splice/core/config/MgmtKey.kt");

/** Pure detection. No I/O — the selftest feeds it directly.
 *
 *  TWO VIEWS OF ONE FILE, on purpose (see [codeOnly]). `code` is MgmtKey.kt with comments and
 *  imports stripped and carries every REQUIRED token, so a token left behind in a `// TODO` cannot
 *  stand in for a deleted call site. `raw` is the file as written and carries the BAN, so the
 *  silent fallthrough cannot be hidden from the wall by commenting it out. Stripping the ban too
 *  would weaken it; not stripping the requirements leaves the wall comment-satisfiable. Both stay
 *  strict this way — the split jw_08 makes across two readers, made here across two views because
 *  both directions are asserted against a single file. */
export function detect(code: string | null, raw: string | null): string[] {
  if (code === null || raw === null) {
    return ["MgmtKey.kt missing — refusing to pass vacuously"];
  }
  if (!code.includes("fun ensure(") && !code.includes("private fun ensure")) {
    return ["MgmtKey.ensure not found (shape changed?) — refusing to pass vacuously"];
  }
  const problems: string[] = [];
  if (raw.includes('discard("unreadable/empty key file')) {
    problems.push(
      "the silent discard fallthrough is still there — an unreadable EXISTING " +
        "key file mints quietly, and every bearer dies with no explanation",
    );
  }
  if (!code.includes("every existing bearer")) {
    problems.push(
      "no loud remint line — the operator learns about the rotation from " +
        "unexplained 401s instead of one log line naming the consequence",
    );
  }
  if (!code.includes("mintedAtMs")) {
    problems.push("mint time is not recorded — doctor cannot flag a suspiciously fresh key");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring. Without this the wall is satisfiable by a COMMENT: delete the
 *  loud `log("... every existing bearer ...")` block and `mintedAtMs = clock()`, leave them behind
 *  as `// SH-12: restore ...`, and both required tokens still match while the remint is silent
 *  again. Proven against this file's own source before the stripper landed. Same stripper
 *  cx_01/cx_02/cx_09/cx_18/jw_08 carry.
 *
 *  Applied ONLY to the required-token view — never to the ban, which reads raw text. Stripping
 *  makes a required token harder to satisfy but would make a banned string easier to hide, so the
 *  two directions get opposite treatment and each stays strict. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? readFileSync(p, "utf8") : null;
}

export const OPEN_FIX =
  'private fun ensure(\ndiscard("unreadable/empty key file falls through to regeneration below")';
export const CLOSED_FIX =
  'private fun ensure(\nlog("[mgmt-key] ... every existing bearer ... is now invalid")\n' +
  "mintedAtMs = clock()";
// The comment-satisfiable shape: the remint is deleted, its text survives as a TODO. Every required
// token is present in the file and none of it runs.
export const COMMENTED_FIX =
  "private fun ensure(\n" +
  '// SH-12: restore log("[mgmt-key] ... every existing bearer ... is now invalid")\n' +
  "// SH-12: restore mintedAtMs = clock()";
// The mirror case: the banned fallthrough commented out rather than removed. The ban reads RAW, so
// this must stay RED — stripping it would let a violation hide behind a `//`.
export const HIDDEN_BAN =
  CLOSED_FIX + '\n// discard("unreadable/empty key file falls through to regeneration below")';

/** One synthetic source, viewed the two ways [detect] takes it — exactly as main() does. */
function both(text: string | null): [string | null, string | null] {
  return [codeOnly(text), text];
}

function selftest(): number {
  const fails: string[] = [];
  if (detect(...both(OPEN_FIX)).length === 0) {
    fails.push("silent discard fallthrough must be RED");
  }
  if (detect(...both(CLOSED_FIX)).length > 0) {
    fails.push(`loud remint + mintedAtMs must be GREEN, got ${pyRepr(detect(...both(CLOSED_FIX)))}`);
  }
  if (detect(...both(CLOSED_FIX.replaceAll("mintedAtMs = clock()", ""))).length === 0) {
    fails.push("no mint timestamp must be RED");
  }
  if (detect(...both("private fun ensure(\nmintedAtMs = clock()")).length === 0) {
    fails.push("a quiet remint (no loud line) must be RED");
  }
  if (detect(...both(COMMENTED_FIX)).length === 0) {
    fails.push("a remint that survives only as a comment must be RED — a mention is not a wiring");
  }
  if (detect(...both(HIDDEN_BAN)).length === 0) {
    fails.push(
      "the banned fallthrough commented out must still be RED — a ban must never be " +
        "hideable behind a `//`",
    );
  }
  if (detect(...both(null)).length === 0) {
    fails.push("missing MgmtKey.kt must be RED, never a vacuous pass");
  }
  if (detect(...both("class MgmtKey")).length === 0) {
    fails.push("an unrecognized shape must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("SH-12 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-12 SELFTEST OK — red on silent fallthrough, quiet remint, missing timestamp, " +
      "commented-out remint, commented-out fallthrough, missing file, and shape change; " +
      "green only on the loud, recorded remint\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const raw = read(KEY);
  const problems = detect(codeOnly(raw), raw);
  if (problems.length > 0) {
    process.stdout.write("SH-12 WALL RED — MgmtKey silently mints a new bearer on ANY read failure:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "SH-12 WALL GREEN: first-run mints stay quiet; a present-but-unreadable key mints loudly and is timestamped.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
