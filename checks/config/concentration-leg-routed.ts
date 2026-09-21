/** checks/config/concentration-leg-routed.ts — the concentration leg is ROUTED, and its definition
 *  still invokes the ratchet with a threshold.
 *
 *  tools/gate/src/lib/routing.ts exists for the analogous defect one surface down: a wall that is
 *  PRESENT but wired to nothing, which is how .rules/kotlin sat dormant for a month under a green
 *  gate. The concentration leg has the same hole in package.json. The gate ladder runs
 *  `npm run --silent gate:concentration` and reports the leg green on exit 0, so the entire leg is
 *  defanged by a ONE-LINE edit:
 *
 *      "gate:concentration": "bun checks/concentration.ts --top 5"
 *
 *  That exits 0 unconditionally, and the gate keeps printing a green "concentration" leg over an
 *  oracle that is no longer grading anything. The gate's own output cannot distinguish the two
 *  states — which is the definition of a fake green.
 *
 *  Two directions, exactly as the rule-routing check asks both:
 *
 *    forward — the gate ladder (tools/gate/config/ladder.json: the rows
 *              build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts registers as Exec tasks
 *              under gateOfRecord) carries a leg whose argv IS `npm run [--silent] gate:concentration`
 *    inverse — that script invokes checks/concentration.ts with --ratchet AND a numeric --max-ratio
 *
 *  Neither half is sufficient alone: a routed script that does not ratchet is the defang above, and
 *  a correct script that nothing runs is the 2026-07-16 dormant-pack scar.
 *
 *  WHY THE INVERSE HALF TOKENIZES INSTEAD OF SUBSTRING-MATCHING (2026-08-18). The first revision of
 *  this guard asked `ORACLE in body` and `"--ratchet" in body.split()` — raw substring tests over
 *  UNPARSED text. A single `#` defeats every one of them, because the required substrings go on
 *  matching once they sit in a shell COMMENT, i.e. in the part of the line that never executes.
 *  Reproduced against the old guard:
 *
 *      "gate:concentration": "true # bun checks/concentration.ts --ratchet --max-ratio 1.8"
 *
 *        -> the guard printed PASS and exited 0, while `npm run --silent gate:concentration` exited
 *           0 having produced NO OUTPUT AT ALL: the only command that ran was `true`. The oracle was
 *           gone and every surface said green.
 *
 *  A guard that reads the text a shell throws away is grading a string, not a command. So the
 *  inverse half strips comments and TOKENIZES — shlex in POSIX mode, which drops everything from
 *  an unquoted `#` to end of line exactly as the shell does, while a `#` inside quotes stays data —
 *  and then asserts on the resulting argv: bun as the runtime, the oracle as argv[1], exactly one
 *  --ratchet, exactly one numeric --max-ratio, and no trailing/control tokens that could mask the
 *  exit. An untokenizable definition (unbalanced quotes) is never counted as evidence.
 *
 *  WHY THE FORWARD HALF READS A TABLE (PR 5, 2026-09-21). Until the restructure the ladder was
 *  checks/gate.sh and this half tokenized its `run` lines, tracked `if`/`fi`, braces and heredocs
 *  (DR-114, DR-133), because a shell script can carry a leg's text in a comment, in dead control
 *  flow, in a function nobody calls or in heredoc data — and each of those was measured passing a
 *  substring guard. The ladder is DATA now: a JSON row is present or absent, its argv is an array
 *  Gradle hands to the process with no shell in between, and there is no control flow to bury a
 *  row in. What remains is exactly what the shell version checked after all that parsing: a row
 *  whose argv is `npm run [--silent|-s] gate:concentration`, middle flags pinned to an allowlist
 *  (DR-51: `--prefix <dir>` reads a DIFFERENT package.json than the one the inverse half validates,
 *  and `--if-present` beside it exits 0 without the oracle ever executing). That the rows in the
 *  file are the tasks in the graph is Gradle's own `verifyLadder` task's claim, inside the gate. An
 *  unreadable or unparseable table fails CLOSED here.
 *
 *  KNOWN LIMIT, stated rather than discovered later: this guards the leg, not itself. Deleting its
 *  call from tools/gate/src/lib/configguard.ts removes this check. That regress is caught one
 *  surface up — checks/concentration-selftest.sh runs the bypasses above as fixtures on a
 *  throwaway copy of the table — but the selftest's own routing is where the regress stops: one
 *  more level of guard, in the gate, is what the repo buys; beyond that the answer is code review,
 *  not another script.
 *
 *  V4-145: converted from concentration-leg-routed.py. shlex.split(comments=True) is pyshim's
 *  transcription of CPython's read_token. V4-158 moved the oracle to checks/concentration.ts, and
 *  RUNTIME and ORACLE below moved with it in the same commit: the pin follows the oracle, not this
 *  guard. The 2664-case corpus was carried across by a counted swap (see its parity.sh).
 *
 *  Run: `bun checks/config/concentration-leg-routed.ts`, and as the config guard of `bun tools/gate rules`.
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { pyFloat, pyRepr, pyReprList, shlexSplit, ValueError } from "../e2e/pyshim.ts";
import { loads, isPyObj, objGet, type PyValue } from "../e2e/pyjson.ts";

const ROOT = resolve(dirname(import.meta.path), "..", "..");
const SCRIPT = "gate:concentration";
const ORACLE = "checks/concentration.ts";
const LADDER = "tools/gate/config/ladder.json";

// argv[0] of the leg must actually run the oracle. Asserting the runtime POSITIVELY is the
// generalisation of "argv[0] is not `true`": blacklisting one no-op leaves `:`, and leaves
// `echo bun checks/concentration.ts --ratchet --max-ratio 1.8`, which satisfies every
// name-and-flag check in this file while executing nothing. A path to bun counts; its basename
// must be exactly `bun`.
const RUNTIME = "bun";

// The leg's argv in the ladder — `npm run [flags] gate:concentration`. Pinned as tokens, so a leg
// whose argv is `true` cannot pass by carrying the script name in its reason.
const LEG_RUNNER = ["npm", "run"];

// MIDDLE FLAGS ARE PINNED TO AN ALLOWLIST (DR-51, 2026-08-30). `npm run <anything> gate:concentration`
// used to satisfy the forward half as long as the first two tokens and the last one matched — but
// npm flags can re-point or disarm the run: `--prefix <dir>` reads a DIFFERENT package.json than the
// one the inverse half validates, and with `--if-present` beside it the leg exits 0 without the
// oracle ever executing, both halves green. Reproduced against the pre-DR-51 guard:
// `npm run --prefix /tmp --if-present gate:concentration` PASSED. Only output shaping is benign;
// everything else is not a routing.
const LEG_FLAGS_ALLOWED = new Set(["--silent", "-s"]);

/** The argv a POSIX shell would actually execute for `line`, or null if it does not tokenize.
 *
 *  comments=true is the entire point: shlex discards an unquoted `#` and everything after it,
 *  exactly as the shell does, while a `#` inside quotes survives as data. null (rather than []) on
 *  unbalanced quotes keeps callers fail-closed — a line nobody can parse is never evidence.
 */
export function tokenize(line: string): string[] | null {
  try {
    return shlexSplit(line, true);
  } catch (e) {
    if (e instanceof ValueError) return null;
    throw e;
  }
}

/** float(token) for the values argparse would accept, or null when float() would raise. */
function floatOrNull(token: string): number | null {
  try {
    return pyFloat(token);
  } catch (e) {
    if (e instanceof ValueError) return null;
    throw e;
  }
}

/** The numeric value of --max-ratio in argv, or null if it is absent or not a number.
 *
 *  Both spellings, because argparse accepts both and a guard that only knows one of them is a guard
 *  the next author trips over for no reason.
 */
export function maxRatioOf(argv: string[]): number | null {
  for (let index = 0; index < argv.length; index++) {
    const token = argv[index];
    let value: string | null;
    if (token === "--max-ratio") value = index + 1 < argv.length ? argv[index + 1] : null;
    else if (token.startsWith("--max-ratio=")) value = token.slice("--max-ratio=".length);
    else continue;
    if (value === null) return null;
    return floatOrNull(value);
  }
  return null;
}

/** Why argv is not exactly `bun ORACLE --ratchet --max-ratio N`, or null. */
export function exactOracleArgvProblem(argv: string[]): string | null {
  if (argv.length < 2 || argv[1] !== ORACLE) {
    return `the oracle must be argv[1], got ${pyReprList(argv.slice(1, 2))}`;
  }
  let ratchets = 0;
  let ratios = 0;
  const unexpected: string[] = [];
  let index = 2;
  while (index < argv.length) {
    const token = argv[index];
    if (token === "--ratchet") {
      ratchets += 1;
      index += 1;
    } else if (token === "--max-ratio") {
      ratios += 1;
      if (index + 1 >= argv.length) {
        unexpected.push(token);
        index += 1;
      } else {
        if (floatOrNull(argv[index + 1]) === null) unexpected.push(...argv.slice(index, index + 2));
        index += 2;
      }
    } else if (token.startsWith("--max-ratio=")) {
      ratios += 1;
      if (floatOrNull(token.slice("--max-ratio=".length)) === null) unexpected.push(token);
      index += 1;
    } else {
      unexpected.push(token);
      index += 1;
    }
  }
  if (ratchets !== 1 || ratios !== 1 || unexpected.length) {
    return "expected one --ratchet and one numeric --max-ratio with no other argv; "
      + `saw ratchet=${ratchets}, max-ratio=${ratios}, unsupported or trailing token(s)=${pyReprList(unexpected)}`;
  }
  return null;
}

/** package.json's gate:concentration really invokes the ratcheting oracle. */
export function inverseProblems(): string[] {
  const found: string[] = [];
  const parsed = loads(readFileSync(join(ROOT, "package.json"), "utf8"));
  const scripts = isPyObj(parsed) ? objGet(parsed, "scripts") : null;
  const declared: PyValue = scripts !== null && isPyObj(scripts) ? objGet(scripts, SCRIPT) : null;
  if (declared === null || typeof declared !== "string") {
    found.push(
      `package.json declares no '${SCRIPT}' script, but the gate ladder (${LADDER}) runs one — the leg `
      + "would fail loudly today, and the moment it does not, concentration is ungated.",
    );
    return found;
  }
  const body = declared;

  const argv = tokenize(body);
  if (argv === null) {
    found.push(
      `'${SCRIPT}' does not tokenize as a shell command (it is defined as: ${pyRepr(body)}) — `
      + "unbalanced quotes. A definition this guard cannot parse is not a definition it will "
      + "vouch for.",
    );
    return found;
  }
  if (!argv.length) {
    found.push(
      `'${SCRIPT}' executes NOTHING (it is defined as: ${pyRepr(body)}) — once shell comments are `
      + "stripped no command is left, so the leg exits 0 without the oracle ever running.",
    );
    return found;
  }

  if (argv[0].split("/").pop() !== RUNTIME) {
    found.push(
      `'${SCRIPT}' does not run bun — argv[0] is ${pyRepr(argv[0])} (it is defined `
      + `as: ${pyRepr(body)}). Whatever follows, the oracle is not what executes; this is the `
      + "`true # <the real command>` bypass, where every required word survives in a comment "
      + "the shell discards.",
    );
  }
  if (!argv.includes(ORACLE)) {
    found.push(
      `'${SCRIPT}' does not invoke ${ORACLE} — it executes ${pyReprList(argv)} (defined as: ${pyRepr(body)}). `
      + "The leg reports on something other than the concentration oracle.",
    );
  }
  if (!argv.includes("--ratchet")) {
    found.push(
      `'${SCRIPT}' does not pass --ratchet — it executes ${pyReprList(argv)} (defined as: ${pyRepr(body)}). `
      + "Without it the oracle prints a table and exits 0 no matter what the census says, and "
      + "`npm run gate` still shows the leg green.",
    );
  }
  if (maxRatioOf(argv) === null) {
    found.push(
      `'${SCRIPT}' does not pass a numeric --max-ratio — it executes ${pyReprList(argv)} (defined as: `
      + `${pyRepr(body)}). --ratchet without one exits 2 with 'a threshold that is not stated at the `
      + "call site is not auditable from the gate's own output'.",
    );
  }
  const exactProblem = exactOracleArgvProblem(argv);
  if (exactProblem !== null) {
    found.push(
      `'${SCRIPT}' is not the exact ratchet command: ${exactProblem}. It executes ${pyReprList(argv)} `
      + `(defined as: ${pyRepr(body)}); shell control operators or trailing commands can mask the `
      + "oracle's exit status.",
    );
  }
  return found;
}

/** The gate ladder really carries that script as a leg's argv, through `npm run`, with nothing between. */
export function forwardProblems(): string[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(readFileSync(join(ROOT, LADDER), "utf8"));
  } catch (e) {
    return [
      `${LADDER} could not be read or parsed (${e instanceof Error ? e.message : String(e)}) — a ladder this `
      + `guard cannot read is not evidence that anything runs '${SCRIPT}'.`,
    ];
  }
  const rows = (parsed as { legs?: unknown }).legs;
  if (!Array.isArray(rows) || rows.length === 0) {
    return [`${LADDER} names no legs — a ladder with no rows runs nothing, '${SCRIPT}' included.`];
  }
  const argvOf = (row: unknown): string[] | null => {
    const command = (row as { command?: unknown }).command;
    return Array.isArray(command) && command.every((t) => typeof t === "string") ? (command as string[]) : null;
  };
  const shaped = rows.filter((row) => {
    const argv = argvOf(row);
    return argv !== null
      && argv.slice(0, 2).join(" ") === LEG_RUNNER.join(" ")
      && argv.slice(-1).join("") === SCRIPT
      && argv.slice(2, -1).every((flag) => LEG_FLAGS_ALLOWED.has(flag));
  });
  if (shaped.length) return [];

  // Name the near-misses. A row that MENTIONS the script proves nothing, so the failure has to show
  // the reader the difference between the text and the argv.
  const mentions = rows.filter((row) => JSON.stringify(row).includes(SCRIPT));
  let detail = "";
  if (mentions.length) {
    const shown = mentions
      .slice(0, 3)
      .map((row) => pyRepr(JSON.stringify(argvOf(row) ?? (row as { task?: string }).task ?? row)))
      .join(" | ");
    detail = ` '${SCRIPT}' does appear in ${mentions.length} row(s), so a substring test would pass: ${shown} — but `
      + `none of them has an argv of exactly \`npm run [--silent] ${SCRIPT}\`. A mention in a row's reason, or `
      + "a row whose argv is something else, is not a routing.";
  }
  return [
    `${LADDER} does not run '${SCRIPT}' — no leg's argv is \`npm run ... ${SCRIPT}\`; a leg invoked any `
    + "other way bypasses the script the inverse half validates, and a leg only mentioned is not routed "
    + `at all. This is the .rules/kotlin failure, one surface up.${detail}`,
  ];
}

export const problems = (): string[] => inverseProblems().concat(forwardProblems());

export function main(): number {
  const found = problems();
  if (found.length) {
    process.stderr.write(`concentration-leg-routed: FAIL (${found.length} problem(s))\n`);
    for (const problem of found) process.stderr.write(`  ✗ ${problem}\n`);
    return 1;
  }
  process.stdout.write("concentration-leg-routed: PASS — the ladder runs the leg, and the leg ratchets against a stated threshold\n");
  return 0;
}

if (import.meta.main) process.exit(main());
