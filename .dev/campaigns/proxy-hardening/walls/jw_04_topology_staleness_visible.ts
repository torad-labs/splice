#!/usr/bin/env bun
/** WALL for JW-04 — an edited splice.toml must be VISIBLY stale, never silently inert.
 *
 *  GAP (RED at authoring, 2026-08-07): topology loads once at boot (locked no-hot-reload decision),
 *  the shim replaces a daemon only on a version mismatch, and nothing anywhere compares the file on
 *  disk to what the daemon booted with — the classic "I changed the config and nothing happened"
 *  dead end.
 *
 *  GREEN requires ALL of (visibility only — hot reload stays deliberately absent):
 *    1. /health publishes the booted topologyDigest + configPath (ControlServer);
 *    2. the daemon can answer "is the on-disk file different now" (topologyStale, recomputed
 *       per request, failing OPEN on an unreadable file);
 *    3. bin/splice-launch warns (non-fatal, names `splice restart`) on a stale topology;
 *    4. doctor renders the digest comparison (WARN + splice restart fix on mismatch).
 *
 *  EXIT 0 = staleness visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Two strippers again — the Kotlin readers and a SHELL
 *  stripper for the shim — plus the DERIVED-MUTANT selftest, which is why the mutants are driven
 *  as a CLI and not only through the corpus.
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
const CONTROL = resolve(ROOT, "gateway/control/src/main/kotlin/splice/control/ControlServer.kt");
const SHIM = resolve(ROOT, "bin/splice-launch");
// HD-25: topologyFreshness — the declaration this wall reads — moved out of DoctorCommand.kt into
// the daemon-section collaborator when that file was decomposed (it was the tree's worst
// concentration row at 8.10). Re-anchored onto the ONE file that now holds it, at the same
// single-file resolution, following the code rather than the god-file it used to live in.
// 2026-08-23: topologyFreshness (digest + topologyStale comparison) lives in
// DoctorHeadChecks.kt. DoctorDaemonChecks only composes the call.
const DOCTOR = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/cli/DoctorHeadChecks.kt");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(control: string | null, shim: string | null, doctor: string | null): string[] {
  for (const [name, text] of [
    ["ControlServer.kt", control],
    ["bin/splice-launch", shim],
    ["DoctorHeadChecks.kt", doctor],
  ] as [string, string | null][]) {
    if (text === null) {
      return [`${name} missing — refusing to pass vacuously`];
    }
  }
  const problems: string[] = [];
  if (!(control ?? "").includes("topologyDigest")) {
    problems.push(
      "/health carries no topologyDigest — no consumer can ever know the " +
        "running daemon booted from different bytes",
    );
  }
  if (!(control ?? "").includes("topologyStale")) {
    problems.push(
      "the daemon never re-compares the on-disk file — every consumer would " +
        "have to reimplement the hash",
    );
  }
  if (!(shim ?? "").includes("topologyStale") && !(shim ?? "").includes("topologyDigest")) {
    problems.push(
      "the launch shim never checks topology staleness — the operator relaunch " +
        "after an edit stays silently inert",
    );
  }
  if (!(shim ?? "").includes("splice restart")) {
    problems.push("the shim's staleness warning does not name the fix (splice restart)");
  }
  if (!(doctor ?? "").includes("topologyDigest") && !(doctor ?? "").includes("topologyStale")) {
    problems.push("doctor never compares the on-disk config to the booted one");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;
// bin/splice-launch is SHELL, not Kotlin: its comment marker is `#`. Running the Kotlin stripper
// over it would miss every `# TODO:` (and the shim's `topologyStale` token sits one line under a
// comment that already spells it) AND eat the `//` in `http://127.0.0.1`. Same law, own marker.
const SHELL_COMMENT = /(?:(?<=\s)|^)#.*?$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  a REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *
 *  Every reader strips because every check in this wall is a REQUIRED token; it asserts no BANNED
 *  string, which is the one direction that must stay raw (the jw_08 split) so a violation cannot
 *  hide inside a comment. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

/** code_only for the shim — the same law spoken in the shell's comment marker. */
export function shellCodeOnly(text: string | null): string | null {
  if (text === null) return null;
  return text.replace(SHELL_COMMENT, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

function readShell(p: string): string | null {
  return existsSync(p) ? shellCodeOnly(readFileSync(p, "utf8")) : null;
}

export const CONTROL_OK = 'put("topologyDigest", d)\nput("topologyStale", stale)';
export const SHIM_OK = 'topologyStale warn "splice restart"';
export const DOCTOR_OK = "health.topologyDigest != localDigest -> WARN";

/** DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
 *  can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
 *  sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
 *  turn detect red, or that token has rotted into always-green furniture. */
export function derivedMutants(): string[] {
  const live = [read(CONTROL), readShell(SHIM), read(DOCTOR)];
  if (detect(live[0], live[1], live[2]).length > 0) {
    return ["derived mutants need the live tree green; the wall is RED right now"];
  }
  const fails: string[] = [];
  const plan: [number, string[]][] = [
    [0, ["topologyDigest"]],
    [0, ["topologyStale"]],
    [1, ["topologyStale", "topologyDigest"]],
    [1, ["splice restart"]],
    [2, ["topologyDigest", "topologyStale"]],
  ];
  for (const [where, tokens] of plan) {
    const mutated = [...live];
    for (const t of tokens) {
      mutated[where] = (mutated[where] ?? "").replaceAll(t, "");
    }
    if (detect(mutated[0], mutated[1], mutated[2]).length === 0) {
      fails.push(`live tree with ${tokens.join("/")} deleted must be RED — furniture token`);
    }
  }
  return fails;
}

function selftest(): number {
  const fails: string[] = [];
  if (detect("controlHealthJson version only", "shim", "doctor").length === 0) {
    fails.push("today's digest-less shape must be RED");
  }
  if (detect(CONTROL_OK, SHIM_OK, DOCTOR_OK).length > 0) {
    fails.push(`published+checked shape must be GREEN, got ${pyRepr(detect(CONTROL_OK, SHIM_OK, DOCTOR_OK))}`);
  }
  if (detect(CONTROL_OK, "shim without the check", DOCTOR_OK).length === 0) {
    fails.push("a shim that never checks must be RED");
  }
  if (detect(CONTROL_OK, SHIM_OK, "doctor blind").length === 0) {
    fails.push("a doctor that never compares must be RED");
  }
  if (detect('put("topologyDigest", d) only', SHIM_OK, DOCTOR_OK).length === 0) {
    fails.push("a daemon that publishes the digest but never re-compares must be RED");
  }
  if (detect(null, SHIM_OK, DOCTOR_OK).length === 0) {
    fails.push("missing files must be RED, never a vacuous pass");
  }
  fails.push(...derivedMutants());
  if (fails.length > 0) {
    process.stdout.write("JW-04 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "JW-04 SELFTEST OK — red on digest-less health, non-checking shim, blind doctor, " +
      "publish-without-recompare, and missing files; green only when staleness is visible\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(CONTROL), readShell(SHIM), read(DOCTOR));
  if (problems.length > 0) {
    process.stdout.write("JW-04 WALL RED — an edited splice.toml is silently inert:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "JW-04 WALL GREEN: the booted topology digest is published, re-compared, and surfaced by shim and doctor.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
