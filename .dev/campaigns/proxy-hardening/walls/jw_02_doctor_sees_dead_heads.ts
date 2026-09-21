#!/usr/bin/env bun
/** WALL for JW-02 — `splice doctor` must see dead heads, not bless a degraded daemon.
 *
 *  GAP (RED at authoring, 2026-08-07): /health already carries heads/readyHeads/failedHeads, but
 *  the CLI's health client extracts only `version` — doctor prints a green "daemon running" and
 *  "Everything checks out." on an install where every head failed to bind, and the operator has to
 *  grep daemon.log for DEGRADED=.
 *
 *  GREEN requires ALL of:
 *    1. ControlPlaneClient parses the head counters (a HealthView, not a bare version string);
 *    2. daemonChecks turns failedHeads > 0 into a FAIL row with a fix, and a still-converging
 *       count into a WARN;
 *    3. doctor probes each configured head's TCP port so bound-but-unassembled is distinguishable
 *       from unbound.
 *
 *  EXIT 0 = doctor sees. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Straight matching plus the shared stripper, with the
 *  DERIVED-MUTANT selftest (DR-35b) preserved — it reads the LIVE sources, so it is exercised by
 *  the CLI run rather than by a corpus.
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
// 2026-08-23: HealthView left ControlPlaneClient.kt when the fetch cluster moved to
// DaemonLock.kt (concentration split). The wall follows the declaration, not the old file.
const CLIENT = resolve(ROOT, "app/src/main/kotlin/splice/app/daemon/DaemonLock.kt");
// HD-25: headChecks + headSummary — BOTH declarations this wall reads — moved out of DoctorCommand.kt
// into their own collaborator when that file was decomposed (it was the tree's worst concentration
// row at 8.10). Re-anchored onto the ONE file that now holds them, at the same single-file
// resolution, following the code rather than the god-file it used to live in — the same repoint
// jw_13 already carries for HeadBoot.kt.
const DOCTOR = resolve(ROOT, "app/src/main/kotlin/splice/app/cli/doctor/DoctorHeadChecks.kt");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(client: string | null, doctor: string | null): string[] {
  if (client === null) {
    return ["DaemonLock.kt missing — refusing to pass vacuously"];
  }
  if (doctor === null) {
    return ["DoctorHeadChecks.kt missing — refusing to pass vacuously"];
  }
  const problems: string[] = [];
  if (!client.includes("HealthView")) {
    problems.push(
      "ControlPlaneClient still extracts only `version` from /health — the " +
        "head counters the shim already waits on never reach doctor",
    );
  }
  if (!doctor.includes("failedHeads")) {
    problems.push(
      "headSummary never reads failedHeads — doctor blesses a daemon whose " +
        "every head failed to start",
    );
  }
  if (!doctor.includes("not listening")) {
    problems.push(
      "no per-head TCP probe — a bound-but-unassembled head is " + "indistinguishable from an unbound one",
    );
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  a REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *
 *  Both readers strip because all three checks here are REQUIRED tokens; this wall asserts no
 *  BANNED string, which is the one direction that must stay on raw text (the jw_08 split) so a
 *  violation cannot hide inside a comment. DoctorHeadChecks.kt already narrates `failedHeads`
 *  twice in KDoc, so without this the doctor half of the wall was satisfiable with zero code. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const CLIENT_OPEN = 'fun healthVersion(port: Int): String? = obj.str("version")';
export const CLIENT_OK = "data class HealthView(...)\nfun healthView(port: Int): HealthView?";
export const DOCTOR_OPEN = "daemonChecks branches on version only";
export const DOCTOR_OK = 'if (failedHeads > 0) FAIL\nadd("head x", ":4101 not listening")';

/** DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
 *  can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
 *  sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
 *  turn detect red, or that token has rotted into always-green furniture. */
export function derivedMutants(): string[] {
  const live = [read(CLIENT), read(DOCTOR)];
  if (detect(live[0], live[1]).length > 0) {
    return ["derived mutants need the live tree green; the wall is RED right now"];
  }
  const fails: string[] = [];
  const plan: [number, string[]][] = [
    [0, ["HealthView"]],
    [1, ["failedHeads"]],
    [1, ["not listening"]],
  ];
  for (const [where, tokens] of plan) {
    const mutated = [...live];
    for (const t of tokens) {
      mutated[where] = (mutated[where] ?? "").replaceAll(t, "");
    }
    if (detect(mutated[0], mutated[1]).length === 0) {
      fails.push(`live tree with ${tokens.join("/")} deleted must be RED — furniture token`);
    }
  }
  return fails;
}

function selftest(): number {
  const fails: string[] = [];
  if (detect(CLIENT_OPEN, DOCTOR_OPEN).length === 0) {
    fails.push("version-only client + blind doctor must be RED");
  }
  if (detect(CLIENT_OK, DOCTOR_OK).length > 0) {
    fails.push(`HealthView + failedHeads + probe must be GREEN, got ${pyRepr(detect(CLIENT_OK, DOCTOR_OK))}`);
  }
  if (detect(CLIENT_OK, DOCTOR_OPEN).length === 0) {
    fails.push("head checks that never read failedHeads must be RED");
  }
  if (detect(CLIENT_OPEN, DOCTOR_OK).length === 0) {
    fails.push("a client without HealthView must be RED");
  }
  if (detect(CLIENT_OK, "reads failedHeads but has no probe").length === 0) {
    fails.push("missing per-head probe must be RED");
  }
  if (detect(null, DOCTOR_OK).length === 0) {
    fails.push("missing files must be RED, never a vacuous pass");
  }
  fails.push(...derivedMutants());
  if (fails.length > 0) {
    process.stdout.write("JW-02 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "JW-02 SELFTEST OK — red on version-only client, failedHeads-blind doctor, missing " +
      "probe, and missing files; green only when doctor sees the degraded state\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(CLIENT), read(DOCTOR));
  if (problems.length > 0) {
    process.stdout.write("JW-02 WALL RED — doctor blesses a daemon with dead heads:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "JW-02 WALL GREEN: doctor reads the head counters, fails on failedHeads, and probes each head port.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
