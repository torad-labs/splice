#!/usr/bin/env bun
/** WALL for SH-14 — a failed daemon.log rotate must self-correct, never wedge the logger forever.
 *
 *  GAP (RED at authoring, 2026-08-07): persistentLogger tracks `written` in memory; when the rotate
 *  Files.move throws (external logrotate removed the file, read-only dir, permissions), onFailure
 *  resets only `writer` and leaves `written` >= MAX_LOG_BYTES — every later line re-enters the
 *  rotate branch, throws BEFORE reaching newBufferedWriter, and daemon.log goes silent permanently
 *  with no error surfaced anywhere.
 *
 *  GREEN requires BOTH:
 *    1. the onFailure branch RECONCILES `written` from the file's real size (absent = 0), restoring
 *       forward progress on the very next line;
 *    2. the failure is announced on stderr (a wedged logger must not be silent about being wedged).
 *
 *  EXIT 0 = self-healing. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). This one carries a LAZY MULTI-LINE regex and a comment
 *  stripper, so the port had to preserve Python's `re.S` + non-greedy semantics exactly rather than
 *  approximate them: `.*?` under DOTALL becomes [\s\S]*? (JS has no DOTALL-lazy shortcut that keeps
 *  the same behaviour under a stripper that leaves blank lines behind), and every re.sub carried a
 *  global flag because Python substitutes all occurrences by default while a JS string replace takes
 *  only the first. Both were driven over the full eight-case positive control AND the live tree.
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
// 2026-08-23: persistentLogger moved to DaemonBoundary.kt. Main.kt is a one-line delegate.
const MAIN = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/DaemonBoundary.kt");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(text: string | null): string[] {
  if (text === null) {
    return ["DaemonBoundary.kt missing — refusing to pass vacuously"];
  }
  if (!text.includes("persistentLogger")) {
    return ["persistentLogger not found (shape changed?) — refusing to pass vacuously"];
  }
  // ANCHORED TO THE WRITE/ROTATE BRANCH, NOT THE FIRST `.onFailure` (V4-123). This used to be a
  // non-anchored first-match, which was correct only while persistentLogger held exactly ONE
  // onFailure. `written`'s size probe (.onFailure just above, in the same function) was added
  // later and sits EARLIER in the file, so first-match started returning the PROBE — which
  // announces but does not reconcile, exactly the state this wall treats as wedged. The result was
  // a FALSE C5 against code that was correct, which is worse than a missed detection: it teaches
  // the reader that a red here is noise.
  //
  // The rotate branch is identified by what only IT does: it clears `writer` before reconciling.
  // Matching on the branch's purpose rather than its position is what survives the next onFailure
  // anyone adds to this function. Refusing vacuously when no such branch exists is deliberate —
  // a wall that silently found nothing would pass, and a logger with no reconcile is precisely
  // the defect.
  const branch = [...text.matchAll(/\.onFailure \{[\s\S]*?\n\s+\}/g)]
    .map((m) => m[0])
    .find((candidate) => candidate.includes("writer = null"));
  if (branch === undefined) {
    return [
      "persistentLogger's write/rotate onFailure branch not found (shape changed?) — " +
        "refusing to pass vacuously",
    ];
  }
  const problems: string[] = [];
  if (!branch.includes("written =")) {
    problems.push(
      "onFailure never reconciles `written` — after one failed rotate every " +
        "later line re-enters the throwing rotate branch and daemon.log is " +
        "silent for the daemon's lifetime",
    );
  }
  if (!branch.includes("System.err")) {
    problems.push(
      "the rotate/write failure is not announced — a wedged logger is silent " + "about being wedged",
    );
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring. Without this the wall is satisfiable by a COMMENT: delete the
 *  reconcile and the announce from the onFailure branch, leave
 *  `// SH-14: restore written = ... / System.err.print("[daemon-log] ...")` in their place, and
 *  both required tokens still match INSIDE the matched branch while the logger wedges on the first
 *  failed rotate again. Proven against this file's own source before the stripper landed. Same
 *  stripper cx_01/cx_02/cx_09/cx_18/jw_08 carry.
 *
 *  Both assertions here are REQUIRED tokens — this wall carries no banned string — so stripping is
 *  the strict direction throughout: it can only make a requirement harder to satisfy, never hide a
 *  violation (the split jw_08 has to make between its two readers does not arise). Line comments
 *  strip to empty lines, so the branch's indentation — which the onFailure regex anchors on —
 *  survives the strip unchanged. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const OPEN_FIX = `persistentLogger
            }.onFailure {
                runCatchingCancellable { writer?.close() }
                writer = null
            }`;
export const CLOSED_FIX = `persistentLogger
            }.onFailure { failure ->
                runCatchingCancellable { writer?.close() }
                writer = null
                written = reconcile()
                System.err.print("rotate failed")
            }`;

// THE FALSE-C5 REGRESSION (V4-123), as a fixture rather than a comment. `written`'s size probe is
// an onFailure that ANNOUNCES but does not reconcile, and it sits EARLIER in persistentLogger than
// the write/rotate branch — so a non-anchored first-match returned the probe and reported "onFailure
// never reconciles written" against code that reconciled correctly. Measured on the real tree
// 2026-09-18: the wall went red on a correct commit (3732a6a1 added that probe), which is the worst
// kind of red because it teaches the reader that a failure here is noise.
export const FALSE_C5_FIX = `persistentLogger
            .onFailure {
                System.err.print("size probe failed")
            }
            .getOrDefault(0L)
        return LogSink { msg ->
            }.onFailure { failure ->
                runCatchingCancellable { writer?.close() }
                writer = null
                written = reconcile()
                System.err.print("rotate failed")
            }`;

// The same shape with the ROTATE branch broken: the wall must still be red, or the anchoring above
// would have traded a false positive for a false negative.
export const FALSE_C5_BROKEN = FALSE_C5_FIX.replaceAll("                written = reconcile()\n", "");

function selftest(): number {
  const fails: string[] = [];
  if (detect(OPEN_FIX).length === 0) fails.push("writer-only reset must be RED");
  if (detect(CLOSED_FIX).length > 0) {
    fails.push(`reconcile + announce must be GREEN, got ${pyRepr(detect(CLOSED_FIX))}`);
  }
  // V4-123: a preceding unrelated onFailure must NOT shadow the rotate branch...
  if (detect(FALSE_C5_FIX).length > 0) {
    fails.push(
      "a size-probe onFailure before the rotate must not produce a false C5, " +
        `got ${pyRepr(detect(FALSE_C5_FIX))}`,
    );
  }
  // ...and anchoring must not have blinded the wall to a genuinely broken rotate.
  if (detect(FALSE_C5_BROKEN).length === 0) {
    fails.push("a rotate branch missing its reconcile must still be RED");
  }
  if (detect(CLOSED_FIX.replaceAll("                written = reconcile()\n", "")).length === 0) {
    fails.push("announce without reconcile must be RED");
  }
  if (
    detect(CLOSED_FIX.replaceAll('                System.err.print("rotate failed")\n', "")).length ===
    0
  ) {
    fails.push("reconcile without announce must be RED");
  }
  if (detect(null).length === 0) {
    fails.push("missing DaemonBoundary.kt must be RED, never a vacuous pass");
  }
  if (detect("fun main() {}").length === 0) {
    fails.push("an unrecognized shape must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("SH-14 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "SH-14 SELFTEST OK — red on writer-only reset, missing reconcile, missing announce, " +
      "missing file, and shape change; green only on the self-healing, announcing rotate\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(MAIN));
  if (problems.length > 0) {
    process.stdout.write("SH-14 WALL RED — a failed daemon.log rotate wedges the logger permanently:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "SH-14 WALL GREEN: a failed rotate reconciles the size from disk and announces itself.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
