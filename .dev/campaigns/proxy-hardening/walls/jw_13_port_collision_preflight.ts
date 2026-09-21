#!/usr/bin/env bun
/** WALL for JW-13 — a duplicate head port must be a named pre-flight failure, not an opaque bind error.
 *
 *  GAP (RED at authoring, 2026-08-08): copy-pasting a [heads.X] block and forgetting to change `port`
 *  surfaces only as "[daemon] head 'b' failed to start: Address already in use" on whichever head
 *  lost the race — no pointer to the sibling holding the port, and (JW-01) only in daemon.log. The
 *  analogous WRAPPER-COMMAND collision is validated precisely (install prints both owners); ports have
 *  no such check.
 *
 *  GREEN requires ALL of:
 *    1. Topology.portCollisions() + portCollisionMessage() next to the head-resolution helpers;
 *    2. doctor's configurationChecks FAILs on a port collision, naming both heads;
 *    3. the daemon's assembleDaemonHeads names the sibling instead of the bare OS error.
 *
 *  EXIT 0 = named pre-flight. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Carries the DERIVED-MUTANT selftest (DR-35b), which reads
 *  the LIVE sources and requires each token to still be load-bearing; that is preserved exactly,
 *  including the early bail when the live tree is already red.
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
const TOPO = resolve(ROOT, "core/src/main/kotlin/splice/core/topology/Topology.kt");
// HD-25: configurationChecks — the declaration this wall reads — moved out of DoctorCommand.kt into
// its own collaborator when that file was decomposed (it was the tree's worst concentration row at
// 8.10). Re-anchored onto the ONE file that now holds it, at the same single-file resolution, the
// same way DAEMON below was repointed at HeadBoot.kt.
const DOCTOR = resolve(ROOT, "app/src/main/kotlin/splice/app/cli/doctor/DoctorConfigChecks.kt");
// assembleDaemonHeads (and its portCollisionMessage pre-flight) moved out of Daemon.kt into its own
// collaborator in the 2026-08-17 decomposition (campaign claude-head, CH target Daemon) — repointed
// the same way the kt-state-paths-single-source ignore was, following the code rather than the
// god-file it used to live in.
const DAEMON = resolve(ROOT, "app/src/main/kotlin/splice/app/head/HeadBoot.kt");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(topo: string | null, doctor: string | null, daemon: string | null): string[] {
  for (const [name, text] of [
    ["Topology.kt", topo],
    ["DoctorConfigChecks.kt", doctor],
    ["HeadBoot.kt", daemon],
  ] as [string, string | null][]) {
    if (text === null) {
      return [`${name} missing — refusing to pass vacuously`];
    }
  }
  const problems: string[] = [];
  if (!(topo ?? "").includes("fun portCollisions(")) {
    problems.push(
      "no Topology.portCollisions() — the wrapper-command collision is validated " +
        "but a duplicate port is not",
    );
  }
  if (!(doctor ?? "").includes("portCollision")) {
    problems.push(
      "doctor's config checks never flag a port collision — a duplicated port " +
        "still surfaces as an opaque per-head bind error",
    );
  }
  if (!(daemon ?? "").includes("portCollision")) {
    problems.push(
      "assembleDaemonHeads does not name the sibling — the daemon log stays " +
        "'Address already in use' with no pointer to the other head",
    );
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const TOPO_OK = "fun portCollisions(): Map<Int, List<String>>\nfun portCollisionMessage(";
export const DOC_OK = "portCollisions().map { FAIL naming both }";
export const DMN_OK = "portCollisionMessage pre-flight in assembleDaemonHeads";

/** DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
 *  can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
 *  sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
 *  turn detect red, or that token has rotted into always-green furniture. */
export function derivedMutants(): string[] {
  const live = [read(TOPO), read(DOCTOR), read(DAEMON)];
  if (detect(live[0], live[1], live[2]).length > 0) {
    return ["derived mutants need the live tree green; the wall is RED right now"];
  }
  const fails: string[] = [];
  const plan: [number, string[]][] = [
    [0, ["fun portCollisions("]],
    [1, ["portCollision"]],
    [2, ["portCollision"]],
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
  if (detect("no helper", "no check", "bare error").length === 0) {
    fails.push("today's no-check shape must be RED");
  }
  if (detect(TOPO_OK, DOC_OK, DMN_OK).length > 0) {
    fails.push(`helper + doctor + daemon must be GREEN, got ${pyRepr(detect(TOPO_OK, DOC_OK, DMN_OK))}`);
  }
  if (detect("no helper", DOC_OK, DMN_OK).length === 0) {
    fails.push("a missing Topology helper must be RED");
  }
  if (detect(TOPO_OK, "no check", DMN_OK).length === 0) {
    fails.push("a doctor that never checks must be RED");
  }
  if (detect(TOPO_OK, DOC_OK, "bare error").length === 0) {
    fails.push("a daemon that never names the sibling must be RED");
  }
  if (detect(null, DOC_OK, DMN_OK).length === 0) {
    fails.push("missing files must be RED, never a vacuous pass");
  }
  fails.push(...derivedMutants());
  if (fails.length > 0) {
    process.stdout.write("JW-13 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "JW-13 SELFTEST OK — red on missing helper, unchecked doctor, bare-error daemon, and " +
      "missing files; green only when a duplicate port is a named pre-flight failure\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(TOPO), read(DOCTOR), read(DAEMON));
  if (problems.length > 0) {
    process.stdout.write("JW-13 WALL RED — a duplicate head port is an opaque bind error:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "JW-13 WALL GREEN: port collisions are a named pre-flight failure in doctor and the daemon.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
