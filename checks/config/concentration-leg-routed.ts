#!/usr/bin/env bun
/** checks/config/concentration-leg-routed.ts — the concentration leg is ROUTED, and its definition
 *  still invokes the ratchet with a threshold.
 *
 *  checks/rule-routing.sh exists for the analogous defect one surface down: a wall that is PRESENT
 *  but wired to nothing, which is how .rules/kotlin sat dormant for a month under a green gate. The
 *  concentration leg has the same hole in package.json. `checks/gate.sh` runs
 *  `npm run --silent gate:concentration` and reports the leg green on exit 0, so the entire leg is
 *  defanged by a ONE-LINE edit:
 *
 *      "gate:concentration": "python3 checks/concentration.py --top 5"
 *
 *  That exits 0 unconditionally, and `npm run gate` keeps printing a green "concentration" leg over
 *  an oracle that is no longer grading anything. The gate's own output cannot distinguish the two
 *  states — which is the definition of a fake green.
 *
 *  Two directions, exactly as rule-routing.sh checks both:
 *
 *    forward — checks/gate.sh actually RUNS the gate:concentration script, through `run` so its real
 *              exit code is captured (a mention in a comment is not a routing)
 *    inverse — that script invokes checks/concentration.py with --ratchet AND a numeric --max-ratio
 *
 *  Neither half is sufficient alone: a routed script that does not ratchet is the defang above, and
 *  a correct script that nothing runs is the 2026-07-16 dormant-pack scar.
 *
 *  WHY THIS TOKENIZES INSTEAD OF SUBSTRING-MATCHING (2026-08-18). The first revision of this guard
 *  asked `ORACLE in body`, `"--ratchet" in body.split()` and `ln.startswith("run ")` — raw substring
 *  tests over UNPARSED text. A single `#` defeats every one of them, because the required substrings
 *  go on matching once they sit in a shell COMMENT, i.e. in the part of the line that never
 *  executes. Both halves were bypassed, and both were REPRODUCED against the old guard before that
 *  rewrite:
 *
 *      "gate:concentration": "true # python3 checks/concentration.py --ratchet --max-ratio 1.8"
 *
 *        -> `bash checks/config-guard.sh` printed `concentration-leg-routed: PASS` and exited 0,
 *           while `npm run --silent gate:concentration` exited 0 having produced NO OUTPUT AT ALL:
 *           the only command that ran was `true`. The oracle was gone and every surface said green.
 *
 *      run "concentration"  true  # gate:concentration disabled pending investigation
 *
 *        -> the forward half passed, because the line does not start with `#` and does start with
 *           `run `, and `gate:concentration` is present — in the comment. The leg ran `true`.
 *
 *  A guard that reads the text a shell throws away is grading a string, not a command. So both
 *  halves strip comments and TOKENIZE — shlex in POSIX mode, which drops everything from an
 *  unquoted `#` to end of line exactly as the shell does, while a `#` inside quotes stays data —
 *  and then assert on the resulting argv. The question changed from "does this text contain the
 *  right words" to "does the command that actually runs invoke the oracle". The inverse assertion
 *  pins the complete argv: a real interpreter, the oracle as argv[1], exactly one --ratchet, exactly
 *  one numeric --max-ratio, and no trailing/control tokens that could mask the exit. The forward
 *  assertion is that a `run` line's COMMAND tokenizes to the npm invocation, not that the line
 *  happens to begin with a prefix.
 *
 *  An untokenizable line (unbalanced quotes) is skipped rather than trusted, so every half of this
 *  guard FAILS CLOSED: nothing that cannot be parsed is ever counted as evidence that something is
 *  routed.
 *
 *  KNOWN LIMIT, stated rather than discovered later: this guards the leg, not itself. Deleting the
 *  `concentration leg routed` line from checks/config-guard.sh removes this check. That regress is
 *  caught one surface up — checks/concentration-selftest.sh runs both bypasses above as fixtures and
 *  also deletes the leg from a throwaway copy of checks/gate.sh — but the selftest's own routing is
 *  where the regress stops, for the same reason rule-routing.sh's does: one more level of guard, in
 *  the gate, is what the repo buys; beyond that the answer is code review, not another script.
 *
 *  V4-145: converted from concentration-leg-routed.py. shlex.split(comments=True) is pyshim's
 *  transcription of CPython's read_token; the oracle it validates is still a .py file, so PYTHON
 *  below still names a python interpreter — that pin moves with the oracle, not with this guard.
 *
 *  Run: `bun checks/config/concentration-leg-routed.ts`, and as part of `bash checks/config-guard.sh`.
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { pyFloat, pyRepr, pyReprList, pySplitlines, shlexSplit, ValueError } from "../e2e/pyshim.ts";
import { loads, isPyObj, objGet, type PyValue } from "../e2e/pyjson.ts";

const ROOT = resolve(dirname(import.meta.path), "..", "..");
const SCRIPT = "gate:concentration";
const ORACLE = "checks/concentration.py";

// argv[0] of the leg must actually run a .py file. Asserting the interpreter POSITIVELY is the
// generalisation of "argv[0] is not `true`": blacklisting one no-op leaves `:`, and leaves
// `echo python3 checks/concentration.py --ratchet --max-ratio 1.8`, which satisfies every
// name-and-flag check in this file while executing nothing.
const PYTHON = /^python(3(\.\d+)?)?$/;

// The command half of the leg line in checks/gate.sh — `run <label> npm run [flags] gate:concentration`.
// Pinned as tokens, so a leg whose command is `true` cannot pass by carrying the script name in a
// trailing comment.
const LEG_RUNNER = ["npm", "run"];

// MIDDLE FLAGS ARE PINNED TO AN ALLOWLIST (DR-51, 2026-08-30). `npm run <anything> gate:concentration`
// used to satisfy the forward half as long as the first two tokens and the last one matched — but
// npm flags can re-point or disarm the run: `--prefix <dir>` reads a DIFFERENT package.json than the
// one the inverse half validates, and with `--if-present` beside it the leg exits 0 without the
// oracle ever executing, both halves green. Reproduced against the pre-DR-51 guard:
// `run "concentration" npm run --prefix /tmp --if-present gate:concentration` PASSED. Only output
// shaping is benign; everything else is not a routing.
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

/** Why argv is not exactly `python ORACLE --ratchet --max-ratio N`, or null. */
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
      `package.json declares no '${SCRIPT}' script, but checks/gate.sh runs one — the leg `
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

  if (!PYTHON.test(argv[0].split("/").pop() as string)) {
    found.push(
      `'${SCRIPT}' does not run a python interpreter — argv[0] is ${pyRepr(argv[0])} (it is defined `
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

// DR-114 reachability: shell control-structure keywords, counted as TOKENS over the whole file so
// a leg wrapped in `if false; then ... fi` — token-identical on its own line — is not accepted as
// a routing. Quoted payloads (`bash -c 'if ...'`) survive shlex as single tokens and never count.
const OPENERS = new Set(["if", "while", "until", "for", "case"]);
const CLOSERS = new Set(["fi", "done", "esac"]);

// DR-133: the docstring above claims only a TOP-LEVEL leg counts, but those five keywords were the
// whole nesting model, so two other constructs held a leg that bash never runs:
//   A  a leg moved into a function body nobody calls  — `disabled_legs() { run "concentration" … }`
//   B  the identical text as heredoc DATA             — `cat <<'EOF' >/dev/null … EOF`
// Both were measured PASSING against the real guard, with the real leg removed; A is a plausible
// refactor artifact if gate.sh ever groups legs into functions and one call site is dropped.
// Braces close A: shlex yields a bare `{` token for `name() {` and a bare `}` for the closer, while
// every brace inside a quoted payload (awk programs, `${VAR}`) stays inside its token and cannot be
// miscounted. A heredoc opener tokenizes as `<<DELIM` / `<<-DELIM` after shlex strips comments and
// quotes, so B is skipped as the data it is — a leg found only there leaves the guard unrouted, and
// the near-miss report below shows the reader the line and why it is text rather than a command.
const BRACE_OPEN = "{";
const BRACE_CLOSE = "}";

/** The delimiter a heredoc redirection on this line opens, or null. `<<` and `<<-` only —
 *  `<<<` is a here-STRING, one line of data with no terminator to search for. */
function heredocDelimiter(argv: string[]): string | null {
  for (const token of argv) {
    if (token.startsWith("<<") && !token.startsWith("<<<")) {
      const delimiter = token.slice(2).replace(/^-/, "");
      if (delimiter) return delimiter;
    }
  }
  return null;
}

/** checks/gate.sh really runs that script, through `run`, so its exit code is captured. */
export function forwardProblems(): string[] {
  const gate = readFileSync(join(ROOT, "checks/gate.sh"), "utf8");
  const routed: string[] = [];
  const buried: string[] = [];
  let depth = 0;
  let delimiter: string | null = null;
  const count = (argv: string[], want: (t: string) => boolean) => argv.filter(want).length;
  for (const line of pySplitlines(gate)) {
    // DR-133: a heredoc body is DATA. Skip to its terminator before anything else, or the leg
    // text inside one reads as a command that runs.
    if (delimiter !== null) {
      if (line.trim() === delimiter) delimiter = null;
      continue;
    }
    const argv = tokenize(line);
    if (argv === null || !argv.length) continue;
    delimiter = heredocDelimiter(argv);
    const command = argv.slice(2); // argv[1] is run()'s label; everything after it is the command
    const shaped = argv[0] === "run"
      && command.slice(0, 2).join(" ") === LEG_RUNNER.join(" ")
      && command.slice(-1).join("") === SCRIPT
      && command.slice(2, -1).every((flag) => LEG_FLAGS_ALLOWED.has(flag));
    // DR-114: only a TOP-LEVEL leg counts. A leg nested in any control structure may never
    // execute (if false), and this guard cannot evaluate an arbitrary condition's truth —
    // unconditional is the only reachability it can prove, and the real gate is a flat script.
    if (shaped && depth === 0) routed.push(line.trim());
    else if (shaped) buried.push(line.trim());
    depth += count(argv, (t) => OPENERS.has(t)) - count(argv, (t) => CLOSERS.has(t));
    // DR-133: brace groups nest exactly like the keyword structures — a function body is the
    // common one, and a leg inside a function nobody calls never executes.
    depth += count(argv, (t) => t === BRACE_OPEN) - count(argv, (t) => t === BRACE_CLOSE);
    depth = Math.max(depth, 0); // an unbalanced closer never retro-unlocks earlier acceptance
  }
  if (routed.length) return [];
  if (buried.length) {
    return [
      `checks/gate.sh runs '${SCRIPT}' only inside a nested scope (${pyRepr(buried[0])}) — a leg `
      + "wrapped in a control structure (if false; then ... fi) or buried in a function body "
      + "nobody calls tokenizes identically but may never execute. The routing must be an "
      + "unconditional top-level `run` leg.",
    ];
  }

  // Name the near-misses. The whole point of the tokenizing rewrite is that a line CONTAINING the
  // script name proves nothing, so the failure has to show the reader the difference between the
  // text and the command.
  const mentions = pySplitlines(gate).filter((line) => line.includes(SCRIPT)).map((line) => line.trim());
  let detail = "";
  if (mentions.length) {
    const shown = mentions.slice(0, 3).map(pyRepr).join(" | ");
    detail = ` '${SCRIPT}' does appear on ${mentions.length} line(s), so the substring test this guard `
      + `used to run would pass: ${shown} — but none of them TOKENIZES to a \`run\` leg whose `
      + `command is \`npm run ... ${SCRIPT}\`. A mention inside a shell comment is not a routing.`;
  }
  return [
    `checks/gate.sh does not run '${SCRIPT}' through \`run\` as \`npm run ... ${SCRIPT}\` — a leg `
    + "invoked any other way has its exit code masked, and a leg only mentioned in a comment is "
    + `not routed at all. This is the .rules/kotlin failure, one surface up.${detail}`,
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
  process.stdout.write("concentration-leg-routed: PASS — gate.sh runs the leg, and the leg ratchets against a stated threshold\n");
  return 0;
}

if (import.meta.main) process.exit(main());
