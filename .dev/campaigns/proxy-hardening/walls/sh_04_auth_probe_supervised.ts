#!/usr/bin/env bun
/** WALL for SH-04 — the auth probe loop must be supervised: a tick failure or loop death may
 *  never silently end per-head auth probing for the daemon's lifetime.
 *
 *  GAP (RED at authoring, 2026-08-07): the per-tick guard (runCatchingCancellable) catches only
 *  IOException/SerializationException/IllegalArgumentException; any other RuntimeException escapes
 *  the while-loop, the coroutine dies, nothing logs it, nothing restarts it, and start() refuses to
 *  re-arm (job != null). The daemon then runs unprobed on that head until restart.
 *
 *  GREEN requires ALL of (supervision-only — the proposal's widened broad catch is FORBIDDEN by
 *  the repo's own walls (kt-no-quality-suppress + ForbiddenSuppress on TooGenericExceptionCaught;
 *  zero broad catches exist in main sources), and the supervisor covers the same throwable class
 *  with bounded churn):
 *    1. the per-tick guard remains (runCatchingCancellable) for the known transient classes;
 *    2. the loop job carries an invokeOnCompletion supervisor that RESTARTS on a non-cancel death;
 *    3. the restart budget is bounded (systemd StartLimitBurst shape) and exhaustion logs a
 *       "permanently down" line for SH-08 to surface.
 *
 *  EXIT 0 = supervised. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Straight string matching plus the shared comment
 *  stripper; the selftest's two comment-satisfiability halves (raw must read GREEN, stripped must
 *  read RED) are the load-bearing part and were transcribed one for one.
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
const LOOP = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/AuthProbeLoop.kt");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(text: string | null): string[] {
  if (text === null) {
    return ["AuthProbeLoop.kt missing — refusing to pass vacuously"];
  }
  if (!text.includes("class AuthProbeLoop")) {
    return ["AuthProbeLoop class not found (shape changed?) — refusing to pass vacuously"];
  }
  const problems: string[] = [];
  if (!text.includes("runCatchingCancellable { probeOnce() }") && text.includes("probeOnce()")) {
    problems.push(
      "the per-tick guard disappeared — known transient classes should still be " +
        "logged ticks without a restart cycle",
    );
  }
  if (!text.includes("invokeOnCompletion")) {
    problems.push(
      "no invokeOnCompletion supervisor — a dead loop stays dead for the " +
        "daemon's lifetime and start() refuses to re-arm",
    );
  }
  if (!text.includes("MAX_RESTARTS")) {
    problems.push(
      "no bounded restart budget — a supervisor without StartLimitBurst is a " +
        "hot restart loop waiting to happen",
    );
  }
  if (!text.includes("permanently down")) {
    problems.push("budget exhaustion is not announced — SH-08 has no line to surface");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
 *  REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *  Proven against this wall's own source: with the whole `launched.invokeOnCompletion { ... }`
 *  supervisor deleted and its literal text left in a TODO, the raw-matching wall printed WALL GREEN
 *  while a dead probe loop stayed dead for the daemon's lifetime again. AuthProbeLoop.kt already
 *  carries a 6-line SH-04 comment naming the supervisor directly above it, so all three required
 *  tokens were one deletion away from a free pass.
 *
 *  Every assertion in detect() is a REQUIRED token — there is no banned string a comment could be
 *  used to HIDE — so the whole read is stripped and the jw_08 raw/code split does not apply here. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const OPEN_FIX = "class AuthProbeLoop\nrunCatchingCancellable { probeOnce() }";
export const CLOSED_FIX =
  "class AuthProbeLoop\nrunCatchingCancellable { probeOnce() }\n" +
  "invokeOnCompletion\nMAX_RESTARTS\n" +
  'log("permanently down")';
export const SUPERVISOR_COMMENTED =
  "class AuthProbeLoop\nrunCatchingCancellable { probeOnce() }\n" +
  "// TODO(SH-04): restore launched.invokeOnCompletion { ... MAX_RESTARTS " +
  'budget ... log("probe permanently down") }';

function selftest(): number {
  const fails: string[] = [];
  if (detect(OPEN_FIX).length === 0) {
    fails.push("unsupervised loop must be RED");
  }
  if (detect(CLOSED_FIX.replaceAll("runCatchingCancellable { probeOnce() }\n", "probeOnce()\n")).length === 0) {
    fails.push("a supervisor that dropped the per-tick guard must be RED");
  }
  if (detect(CLOSED_FIX).length > 0) {
    fails.push(`broad tick catch + supervisor + budget must be GREEN, got ${pyRepr(detect(CLOSED_FIX))}`);
  }
  if (detect(CLOSED_FIX.replaceAll("invokeOnCompletion\n", "")).length === 0) {
    fails.push("no supervisor must be RED");
  }
  if (detect(CLOSED_FIX.replaceAll("MAX_RESTARTS\n", "")).length === 0) {
    fails.push("no restart budget must be RED");
  }
  if (detect(null).length === 0) {
    fails.push("missing AuthProbeLoop.kt must be RED, never a vacuous pass");
  }
  // HD-26 comment-satisfiability control, in the two halves that make it a proof: the raw shape is
  // the BUG (green with the supervisor deleted), the stripped shape is the FIX.
  if (detect(SUPERVISOR_COMMENTED).length > 0) {
    fails.push(
      "the raw shape must read GREEN — otherwise this fixture is not the bug and " +
        "the control below proves nothing",
    );
  }
  if (detect(codeOnly(SUPERVISOR_COMMENTED)).length === 0) {
    fails.push(
      "a supervisor that survives only as comment text must be RED — this wall must " +
        "read code_only, never raw file text",
    );
  }
  if (fails.length > 0) {
    process.stdout.write("SH-04 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-04 SELFTEST OK — red on narrow catch, missing supervisor, missing budget, missing " +
      "announce, and missing file; green only on the fully supervised loop\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(LOOP));
  if (problems.length > 0) {
    process.stdout.write("SH-04 WALL RED — the auth probe loop is unsupervised:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "SH-04 WALL GREEN: tick failures log, loop deaths restart under a bounded budget, exhaustion announces.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
