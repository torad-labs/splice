#!/usr/bin/env bun
/** WALL for JW-08 — there must be a `splice logs` verb and doctor must name the log path.
 *
 *  GAP (RED at authoring, 2026-08-08): every remediation ends at "check daemon.log" (the control
 *  plane says so literally), yet there is no CLI verb to reach it, and doctor prints the STATE dir
 *  (~/.claude-codex/state) while daemon.log lives in the SIBLING logs dir — so the one path doctor
 *  prints does not contain the logs. The only log surface (the dashboard panel) needs the daemon up,
 *  a browser, and the mgmt-key: exactly what is broken when you need logs.
 *
 *  GREEN requires ALL of:
 *    1. a Logs command in the verb table (reachable, daemon-independent — reuses LogFileSource);
 *    2. doctor prints a row pointing at the LOGS dir daemon.log (not the state dir);
 *    3. the "check daemon.log" remediation strings name `splice logs`.
 *
 *  EXIT 0 = reachable + signposted. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). The distinguishing piece is readTree: it recursively
 *  concatenates every .kt under the control plane, RAW, and reads null for a missing or .kt-less
 *  tree — a vacuity RED, never an empty sweep that trivially satisfies a NEGATIVE assertion. This
 *  is also the wall whose raw/code split the other walls cite, so both directions are preserved.
 */
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
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
// 2026-08-23: the `"logs"` verb table entry lives in InstallCommand.kt.
// Command.kt still owns LogsCommand but not the quoted verb this wall pins.
const COMMAND = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/cli/InstallCommand.kt");
// HD-25: the logsDir row this wall reads is inside daemonChecks, which moved out of DoctorCommand.kt
// into the daemon-section collaborator when that file was decomposed (it was the tree's worst
// concentration row at 8.10). Re-anchored onto the ONE file that now holds it, at the same
// single-file resolution. NOT widened to the cli package: unlike the CONTROL_DIR sweep below, this
// is a REQUIRED token, so a directory read would let any sibling satisfy it.
const DOCTOR = resolve(ROOT, "gateway/app/src/main/kotlin/splice/app/cli/DoctorDaemonChecks.kt");
// HD-24: the remediation strings this key polices left ControlServer.kt when the control plane
// split into splice.control + splice.control.api ("refresh failed — run: splice logs" now lives in
// api/AuthRoutes.kt), which left the single-file read policing a file that carries no remediation
// text at all. A file LIST cannot fix this key the way it fixed JW-06's: this is a NEGATIVE
// assertion, so the file that would carry the violation need not exist yet. Scoped to the
// NEIGHBOURHOOD instead — every .kt under the control plane, recursively — the same remedy CX-18
// uses for its ban, so a new route file is covered the moment it is written, with no wall edit.
const CONTROL_DIR = resolve(ROOT, "gateway/control/src/main/kotlin/splice/control");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(command: string | null, doctor: string | null, control: string | null): string[] {
  for (const [name, text] of [
    ["InstallCommand.kt", command],
    ["DoctorDaemonChecks.kt", doctor],
    ["control plane sources", control],
  ] as [string, string | null][]) {
    if (text === null) {
      return [`${name} missing — refusing to pass vacuously`];
    }
  }
  const problems: string[] = [];
  if (!(command ?? "").includes('"logs"')) {
    problems.push(
      "no `logs` verb in the parse table — every remediation ends at daemon.log " +
        "with no CLI path to it",
    );
  }
  if (!(doctor ?? "").includes("logsDir")) {
    problems.push(
      "doctor never names the logs dir — it prints the state dir, which does " + "NOT contain daemon.log",
    );
  }
  if ((control ?? "").includes("check daemon.log")) {
    problems.push("a 'check daemon.log' remediation string still does not name `splice logs`");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
 *  REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *
 *  Applied to read() (which feeds the required tokens) and deliberately NOT to readTree(), which
 *  feeds the BAN. The two directions want opposite treatment: stripping makes a required token
 *  harder to satisfy, but would make a banned string easier to hide. Both stay strict this way. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

/** Concatenate every .kt under `d`, recursively. A missing or .kt-less tree reads as null
 *  (vacuity RED) rather than as an empty sweep that trivially satisfies a negative assertion.
 *
 *  Raw text on purpose — see codeOnly: this feeds the ban, where a comment must still count. */
export function readTree(d: string): string | null {
  if (!existsSync(d) || !statSync(d).isDirectory()) return null;
  const found: string[] = [];
  const stack = [d];
  while (stack.length > 0) {
    const cur = stack.pop() as string;
    for (const e of readdirSync(cur, { withFileTypes: true })) {
      const p = resolve(cur, e.name);
      if (e.isDirectory()) stack.push(p);
      else if (e.name.endsWith(".kt")) found.push(p);
    }
  }
  found.sort();
  if (found.length === 0) return null;
  return found.map((p) => readFileSync(p, "utf8")).join("\n");
}

export const CMD_OK = '"logs" to { a -> Logs(a) }';
export const DOC_OK = 'DoctorCheck("logs", INFO, statePaths.logsDir...daemon.log)';
export const CTRL_OK = 'put("note", "refresh failed — run: splice logs")';

/** DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
 *  can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
 *  sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
 *  turn detect red, or that token has rotted into always-green furniture. */
export function derivedMutants(): string[] {
  const live = [read(COMMAND), read(DOCTOR), readTree(CONTROL_DIR)];
  if (detect(live[0], live[1], live[2]).length > 0) {
    return ["derived mutants need the live tree green; the wall is RED right now"];
  }
  const fails: string[] = [];
  const plan: [number, string[]][] = [
    [0, ['"logs"']],
    [1, ["logsDir"]],
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
  if (detect("verb table no logs", "prints state dir only", "check daemon.log").length === 0) {
    fails.push("today's no-verb shape must be RED");
  }
  if (detect(CMD_OK, DOC_OK, CTRL_OK).length > 0) {
    fails.push(`verb + doctor row + fixed string must be GREEN, got ${pyRepr(detect(CMD_OK, DOC_OK, CTRL_OK))}`);
  }
  if (detect("verb table no logs", DOC_OK, CTRL_OK).length === 0) {
    fails.push("a missing logs verb must be RED");
  }
  if (detect(CMD_OK, "state dir only", CTRL_OK).length === 0) {
    fails.push("a doctor that never names logsDir must be RED");
  }
  if (detect(CMD_OK, DOC_OK, "check daemon.log").length === 0) {
    fails.push("a lingering 'check daemon.log' string must be RED");
  }
  if (detect(null, DOC_OK, CTRL_OK).length === 0) {
    fails.push("missing files must be RED, never a vacuous pass");
  }
  // HD-24 staleness control. Every case above stayed correct through the ControlServer split
  // while the READER went blind, so the reader is a positive control too: the swept tree must
  // actually reach the remediation strings this wall's negative assertion is written against.
  const live = readTree(CONTROL_DIR);
  if (live === null) {
    fails.push(
      `the control-plane sweep found no sources under ${CONTROL_DIR} — the reader ` +
        "is pointed at nothing and the negative assertion is vacuous",
    );
  } else if (!live.includes("splice logs")) {
    fails.push(
      "the control-plane sweep no longer reaches any `splice logs` remediation " +
        "string — the negative assertion has nothing left to police",
    );
  }
  fails.push(...derivedMutants());
  if (fails.length > 0) {
    process.stdout.write("JW-08 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "JW-08 SELFTEST OK — red on missing verb, state-dir-only doctor, lingering daemon.log " +
      "string, and missing files; green only when logs are reachable and signposted\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(COMMAND), read(DOCTOR), readTree(CONTROL_DIR));
  if (problems.length > 0) {
    process.stdout.write("JW-08 WALL RED — no `splice logs`, and the log path is unsignposted:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "JW-08 WALL GREEN: `splice logs` exists (daemon-independent) and doctor names the log path.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
