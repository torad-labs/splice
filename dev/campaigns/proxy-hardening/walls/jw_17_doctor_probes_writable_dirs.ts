#!/usr/bin/env bun
/** WALL for JW-17 — doctor must PROVE the state and log dirs are writable, not just print paths.
 *
 *  GAP (RED at authoring, 2026-08-08): three subsystems degrade silently when ~/.claude-codex is
 *  unwritable or full — daemon.log creation (swallowed), config PATCH persistence (best-effort), and
 *  usage/perf/compact appends (best-effort by design). The operator sees "dashboard empty, logs
 *  empty, config changes don't survive restart" with no error anywhere. doctor prints the state-dir
 *  path but never touches it.
 *
 *  GREEN requires ALL of:
 *    1. doctor writes-and-deletes a dot-prefixed probe file in the state AND log dirs;
 *    2. the probe is removed in a finally (doctor stays non-mutating in spirit);
 *    3. a failure is a FAIL row carrying an actionable fix (chmod / df pointer).
 *
 *  EXIT 0 = probed. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Each dir is its own pinned leg, whitespace-tolerant
 *  because the live logs-dir call breaks the line after the paren, and the DR-35c per-site
 *  knockouts need SINGLE-occurrence replacement — so the sub is a non-global RegExp, matching
 *  Python's count=1, not a global replace that would knock out both probes at once and prove
 *  nothing about per-site rot.
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
// HD-25: stateInfo and BOTH writableProbe call sites are inside daemonChecks, which moved out of
// DoctorCommand.kt into the daemon-section collaborator when that file was decomposed (it was the
// tree's worst concentration row at 8.10). Re-anchored onto the ONE file that now holds the call
// sites, at the same single-file resolution; the PROBE half below is unmoved.
const DOCTOR = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/cli/DoctorDaemonChecks.kt");
// The probe helper lives in its own file (DoctorCommand.kt was at the file function budget); the
// wall reads both so a legitimate split cannot read as a gap.
const PROBE = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/cli/DoctorProbeWrite.kt");

const STATE_PROBE_RE = /writableProbe\(\s*"state dir"/;
const LOGS_PROBE_RE = /writableProbe\(\s*"logs dir"/;

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(doctor: string | null): string[] {
  if (doctor === null) {
    return ["DoctorDaemonChecks.kt missing — refusing to pass vacuously"];
  }
  if (!doctor.includes("stateInfo")) {
    return ["doctor state section not found (shape changed?) — refusing to pass vacuously"];
  }
  const problems: string[] = [];
  // DR-35c (codex catch, 2026-08-30): one presence token let the docstring's own AND rot —
  // replacing only the state-dir probe with an INFO row left the logs probe satisfying the check.
  // Each dir the docstring promises is now its own pinned leg, whitespace-tolerant because the
  // live logs-dir call breaks the line after the paren. The old probeWritable alternate spelling
  // is dropped: exact-pin, a helper rename reds fail-closed instead of passing unexamined.
  if (!STATE_PROBE_RE.test(doctor)) {
    problems.push(
      "doctor never probes the STATE dir for writability — an unwritable " +
        "~/.claude-codex degrades daemon.log, config persistence, and " +
        "usage/perf/compact appends silently",
    );
  }
  if (!LOGS_PROBE_RE.test(doctor)) {
    problems.push(
      "doctor never probes the LOGS dir for writability — daemon.log lives in " +
        "the sibling logs dir (JW-08), and a state-dir-only probe misses it",
    );
  }
  if (!doctor.includes(".delete") && !doctor.includes("deleteIfExists")) {
    problems.push("the probe file is not removed — doctor must stay non-mutating in spirit");
  }
  if (!doctor.includes("chmod") && !doctor.includes("df -h")) {
    problems.push("a writability FAIL carries no actionable fix (chmod / df pointer)");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry.
 *
 *  Every assertion here is a REQUIRED token (a probe, a delete, an actionable fix) — this wall
 *  carries no banned string — so BOTH readers are stripped. Stripping only ever makes a required
 *  token harder to satisfy; the direction that must stay raw is a ban, and there is none. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const OPEN_FIX = "stateInfo = listOf(state dir path only)";
export const CLOSED_FIX =
  'stateInfo\nwritableProbe("state dir", statePaths.stateDir)\n' +
  'writableProbe(\n    "logs dir",\n    statePaths.logsDir,\n)\n' +
  "try { write } finally { Files.deleteIfExists(probe) }\n" +
  'fix = "chmod u+rwx <dir>"';
// DR-35c: codex's exact reproduced false green — ONE probe swapped for an INFO row while the
// other survives. Both directions, so neither dir's probe can rot behind the other's.
export const STATE_SWAPPED = CLOSED_FIX.replaceAll(
  'writableProbe("state dir", statePaths.stateDir)',
  'DoctorCheck("state dir", CheckStatus.INFO, path)',
);
export const LOGS_SWAPPED = CLOSED_FIX.replaceAll(
  'writableProbe(\n    "logs dir",\n    statePaths.logsDir,\n)',
  'DoctorCheck("logs dir", CheckStatus.INFO, path)',
);

/** DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
 *  can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
 *  sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
 *  turn detect red, or that token has rotted into always-green furniture. */
export function derivedMutants(): string[] {
  const live = [(read(DOCTOR) || "") + "\n" + (read(PROBE) || "")];
  if (detect(live[0]).length > 0) {
    return ["derived mutants need the live tree green; the wall is RED right now"];
  }
  const fails: string[] = [];
  const plan: [number, string[]][] = [
    [0, ["writableProbe", "probeWritable"]],
    [0, [".delete", "deleteIfExists"]],
    [0, ["chmod", "df -h"]],
  ];
  for (const [where, tokens] of plan) {
    const mutated = [...live];
    for (const t of tokens) {
      mutated[where] = (mutated[where] ?? "").replaceAll(t, "");
    }
    if (detect(mutated[0]).length === 0) {
      fails.push(`live tree with ${tokens.join("/")} deleted must be RED — furniture token`);
    }
  }
  // DR-35c: all-at-once deletion hid per-site rot — knocking out ONE live probe (codex's INFO-row
  // swap) left the other satisfying the old single-token check. Each live call site must be
  // load-bearing on its own.
  for (const label of ["state dir", "logs dir"]) {
    // SINGLE occurrence, like Python's count=1: a global replace would knock out both sites.
    const knocked = live[0].replace(new RegExp(`writableProbe\\(\\s*"${label}"`), `disabledProbe("${label}"`);
    if (knocked === live[0]) {
      fails.push(
        `live tree has no writableProbe("${label}") site to knock out — ` +
          "the wall and the tree disagree about the probe inventory",
      );
    } else if (detect(knocked).length === 0) {
      fails.push(
        `live tree with only the ${label} probe knocked out must be RED — ` +
          "per-site rot is invisible behind the sibling probe",
      );
    }
  }
  return fails;
}

function selftest(): number {
  const fails: string[] = [];
  if (detect(OPEN_FIX).length === 0) {
    fails.push("path-only doctor must be RED");
  }
  if (detect(CLOSED_FIX).length > 0) {
    fails.push(`probing doctor must be GREEN, got ${pyRepr(detect(CLOSED_FIX))}`);
  }
  if (detect(CLOSED_FIX.replaceAll("try { write } finally { Files.deleteIfExists(probe) }\n", "")).length === 0) {
    fails.push("a probe that never deletes must be RED");
  }
  if (detect(CLOSED_FIX.replaceAll('fix = "chmod u+rwx <dir>"', "")).length === 0) {
    fails.push("a fixless writability failure must be RED");
  }
  if (detect(STATE_SWAPPED).length === 0) {
    fails.push(
      "the state-dir probe swapped for an INFO row must be RED even while the logs " +
        "probe survives (DR-35c)",
    );
  }
  if (detect(LOGS_SWAPPED).length === 0) {
    fails.push(
      "the logs-dir probe swapped for an INFO row must be RED even while the state " +
        "probe survives (DR-35c)",
    );
  }
  if (detect(null).length === 0) {
    fails.push("a missing DoctorDaemonChecks.kt must be RED, never a vacuous pass");
  }
  fails.push(...derivedMutants());
  if (fails.length > 0) {
    process.stdout.write("JW-17 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "JW-17 SELFTEST OK — red on path-only, single-probe-swapped, non-deleting, fixless " +
      "shapes and missing file; green only when doctor probes BOTH dirs and cleans up\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const doctor = read(DOCTOR);
  const problems = detect(doctor ? (doctor || "") + "\n" + (read(PROBE) || "") : null);
  if (problems.length > 0) {
    process.stdout.write("JW-17 WALL RED — doctor never checks the state/log dirs are writable:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "JW-17 WALL GREEN: doctor probes the state and log dirs for writability, with a fix, and cleans up.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
