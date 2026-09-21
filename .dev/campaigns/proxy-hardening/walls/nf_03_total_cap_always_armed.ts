#!/usr/bin/env bun
/** WALL for NF-03 — the whole-turn wall clock must be enforced for the WHOLE turn.
 *
 *  GAP (RED at authoring, 2026-08-07): TurnWatchdog.totalCap is only sampled by the poller that
 *  launchIn() starts INSIDE the successful-response block (TurnDriver) and cancels in that block's
 *  finally — so during connect, headers-wait, retry backoff, refresh, and between fold/re-anchor
 *  rounds NOTHING enforces the cap. An N-round turn gets N x upstreamTimeoutMs of budget against a
 *  single totalCap, holding its InflightGate slot the whole time.
 *
 *  GREEN requires BOTH:
 *    1. TurnWatchdog exposes a turn-scoped total-cap poller (fun launchTotalCap) that samples
 *       elapsed >= totalCap independent of any open stream, setting the typed sentinel BEFORE
 *       cancelling — identical breach semantics to launchIn;
 *    2. TurnDriver launches it (launchTotalCap( call site) alongside the whole-turn client pinger,
 *       NOT inside the response block that launchIn already owns.
 *  The idle tiers stay with launchIn (they need the slot) — this wall also refuses to pass if
 *  launchIn disappears, so the cap poller cannot silently REPLACE idle enforcement.
 *
 *  EXIT 0 = the cap is armed for the whole turn.  EXIT 1 = the gap is open.
 *  --selftest = the POSITIVE CONTROL (gate check C6).
 *
 *  V4-154: converted to TypeScript (bun). Three mechanisms had to survive exactly: the
 *  offset-preserving string masker (including the triple-quoted branch), the brace-ancestry
 *  snapshot taken BEFORE the char at that position is processed, and the prefix-dominance test
 *  (an ancestor scope is fine, a nested one is not). The corpus carries one case per DR-35x
 *  reproduced false green.
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
const WATCHDOG = resolve(ROOT, "upstream/src/main/kotlin/splice/upstream/retry/Watchdog.kt");
// 2026-08-23: the launchTotalCap call site lives in TurnOneDrive.kt after the
// drive split. Watchdog.kt still owns the declaration.
const DRIVER = resolve(ROOT, "gateway/gateway/src/main/kotlin/splice/gateway/head/TurnOneDrive.kt");

/** Blank Kotlin string/char literals without moving offsets (sh_10's idiom), so a brace inside
 *  a log template cannot corrupt the scope stacks below. */
export function maskStrings(text: string): string {
  const chars = [...text];
  let i = 0;
  while (i < chars.length) {
    let end: number;
    if (text.startsWith('"""', i)) {
      const close = text.indexOf('"""', i + 3);
      end = close < 0 ? chars.length : close + 3;
    } else if (chars[i] === '"' || chars[i] === "'") {
      const quote = chars[i];
      end = i + 1;
      while (end < chars.length) {
        if (chars[end] === "\\") {
          end += 2;
        } else if (chars[end] === quote) {
          end += 1;
          break;
        } else {
          end += 1;
        }
      }
    } else {
      i += 1;
      continue;
    }
    for (let at = i; at < Math.min(end, chars.length); at++) {
      if (!"\r\n".includes(chars[at])) {
        chars[at] = " ";
      }
    }
    i = end;
  }
  return chars.join("");
}

/** Brace ancestry of each position, one lexical pass over the masked text. */
export function scopesAt(text: string, positions: number[]): Map<number, number[]> {
  const targets = [...new Set(positions)].sort((a, b) => a - b);
  const structure = maskStrings(text);
  const result = new Map<number, number[]>();
  const stack: number[] = [];
  let target = 0;
  for (let at = 0; at < structure.length; at++) {
    const ch = structure[at];
    while (target < targets.length && targets[target] === at) {
      result.set(targets[target], [...stack]);
      target += 1;
    }
    if (ch === "{") {
      stack.push(at);
    } else if (ch === "}" && stack.length > 0) {
      stack.pop();
    }
  }
  while (target < targets.length) {
    result.set(targets[target], [...stack]);
    target += 1;
  }
  return result;
}

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(watchdogText: string | null, driverText: string | null): string[] {
  if (watchdogText === null) {
    return ["Watchdog.kt missing — refusing to pass vacuously"];
  }
  if (driverText === null) {
    return ["TurnOneDrive.kt missing — refusing to pass vacuously"];
  }
  const problems: string[] = [];
  // DR-35e (codex catch #3, 2026-08-31): every token scan below runs on MASKED text — a
  // compilable raw-string decoy carrying the anchored launch line satisfied the unmasked finds/
  // counts/regexes while the live poller was an inert Job(). code_only strips comments but not
  // strings; scopesAt masked internally, which only hid the gap. Mask once, scan everywhere.
  const wd = maskStrings(watchdogText);
  if (!wd.includes("fun launchIn(")) {
    return [
      "launchIn poller not found in Watchdog.kt (shape changed?) — the idle tiers lost " +
        "their enforcer; refusing to pass vacuously",
    ];
  }
  const capSites = maskStrings(driverText).replaceAll("fun launchTotalCap(", "");
  if (!wd.includes("fun launchTotalCap(")) {
    problems.push(
      "no launchTotalCap on TurnWatchdog — totalCap is only sampled while an " +
        "upstream stream is open (launchIn), never during connect/backoff/refresh/" +
        "between-rounds",
    );
  } else if (!capSites.includes("launchTotalCap(")) {
    problems.push(
      "launchTotalCap exists but the turn drive never launches it — the whole-turn " + "cap is still stream-scoped",
    );
  } else {
    // DR-35a: presence was not placement — the launch could move AFTER roundRun.run (the rounds
    // execution this wall exists to cover) and stay green, re-creating the stream-scoped bug
    // the docstring forbids. The drive is sequential: the cap must be armed BEFORE the rounds.
    const runAt = capSites.indexOf("roundRun.run(");
    if (runAt === -1) {
      problems.push(
        "roundRun.run( not found in TurnOneDrive.kt (shape changed?) — cannot " +
          "verify the cap arms before the rounds; refusing to pass vacuously",
      );
    } else if (capSites.indexOf("launchTotalCap(") > runAt) {
      problems.push(
        "launchTotalCap launches AFTER roundRun.run — the cap poller is " +
          "rounds-scoped again (the placement half-fix): connect/headers-wait/" +
          "backoff before the first round are uncovered",
      );
    } else if (
      // DR-35c (codex catch, 2026-08-30): order alone accepted CONDITIONAL arming — `val capPoller
      // = if (pingClient) drive.watchdog.launchTotalCap(...) else Job()` keeps the call lexically
      // before the rounds while arming the cap on only one path. The call site must be a direct,
      // unconditionally-executed val assignment (the live TurnOneDrive shape), and there must be
      // exactly ONE site, so a compliant decoy cannot vouch for a conditional real one. A reshaped
      // future call site reds fail-closed rather than passing unexamined.
      // DR-35f (codex catch #4, 2026-08-31): the anchor pinned the CALLEE but not the ARGUMENTS —
      // `launchTotalCap(self, if (pingClient) turnJob else Job())` compiled, matched the prefix,
      // and armed the poller against a THROWAWAY Job on non-ping paths: breach cancelled nothing.
      // The whole argument list is pinned to the live `(self, turnJob)` shape; any reshape reds.
      [...capSites.matchAll(/launchTotalCap\(/g)].length !== 1 ||
      !/^[ \t]*val\s+\w+\s*=\s*drive\.watchdog\.launchTotalCap\(self,\s*turnJob\)\s*$/m.test(capSites)
    ) {
      problems.push(
        "the launchTotalCap call site is not exactly one unconditional " +
          "`val x = drive.watchdog.launchTotalCap(self, turnJob)` statement — a " +
          "conditional/indirect launch, or any TARGET other than the bare turnJob, " +
          "arms the whole-turn cap on only some paths or against a throwaway job",
      );
    } else {
      // DR-35d (codex catch #2, 2026-08-31): the line anchor cannot see ENCLOSING control
      // flow — a multi-line `if (pingClient) { val armed = launchTotalCap(...); armed }`
      // puts a perfectly-anchored val at line start inside the branch. Dominance proof:
      // the launch site's brace ancestry must be a PREFIX of the run site's (ancestor or
      // same block) — then launch-before-run in a straight-line body means the cap is armed
      // on every path that reaches the rounds. A launch nested in any block the run is not
      // in (an if arm, a when branch) has a brace the run lacks, and reds.
      const launchAt = capSites.indexOf("launchTotalCap(");
      const scopes = scopesAt(capSites, [launchAt, runAt]);
      const launchScope = scopes.get(launchAt) as number[];
      const runScope = scopes.get(runAt) as number[];
      const prefix =
        launchScope.length <= runScope.length &&
        launchScope.every((v, idx) => runScope[idx] === v);
      if (!prefix) {
        problems.push(
          "launchTotalCap sits inside a block that roundRun.run is not in " +
            "(a conditional branch) — the whole-turn cap is armed on only " +
            "some paths to the rounds",
        );
      }
    }
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry.
 *
 *  Both readers are stripped: every leg here is a REQUIRED token (launchIn, launchTotalCap, the
 *  TurnDriver launch site) and this wall carries no banned string, which is the only direction
 *  that would have to stay raw. It matters most for the driver leg — Watchdog's KDoc already
 *  names [launchIn] and launchTotalCap in prose, so a commented-out launch site would read as a
 *  live one. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const WD_OPEN = "public fun launchIn(scope: CoroutineScope, slot: InflightGate.Slot, target: Job): Job =";
export const WD_CLOSED =
  WD_OPEN + "\n    public fun launchTotalCap(scope: CoroutineScope, target: Job): Job =";
export const DRV_OPEN = "val pinger = if (pingClient) self.launchClientPinger(drive, turnJob) else null";
export const DRV_CLOSED =
  DRV_OPEN +
  "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)" +
  "\n roundRun.run(drive, self, turnJob)";
// DR-35a placement mutants: the launch exists but AFTER the rounds (the half-fix), and a drive
// whose rounds call vanished (must refuse to pass on shape drift, not pass vacuously).
export const DRV_LATE =
  DRV_OPEN +
  "\n roundRun.run(drive, self, turnJob)" +
  "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)";
export const DRV_NO_RUN =
  DRV_OPEN + "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)";
// DR-35c mutants: codex's exact reproduced false green (conditional arming holds lexical order),
// and a compliant decoy beside a conditional real site (exactly-one must refuse the pair).
export const DRV_CONDITIONAL =
  DRV_OPEN +
  "\n val capPoller = if (pingClient) drive.watchdog.launchTotalCap(self, turnJob) else Job()" +
  "\n roundRun.run(drive, self, turnJob)";
export const DRV_DECOY =
  DRV_OPEN +
  "\n val decoy = drive.watchdog.launchTotalCap(self, turnJob)" +
  "\n val capPoller = if (pingClient) drive.watchdog.launchTotalCap(self, turnJob) else Job()" +
  "\n roundRun.run(drive, self, turnJob)";
// DR-35d: codex's second reproduced false green — the multi-line nested conditional keeps a
// line-anchored val INSIDE the branch, beating the anchor leg; only scope dominance sees it.
export const DRV_NESTED =
  DRV_OPEN +
  "\n val capPoller = if (pingClient) {" +
  "\n     val armed = drive.watchdog.launchTotalCap(self, turnJob)" +
  "\n     armed" +
  "\n } else Job()" +
  "\n roundRun.run(drive, self, turnJob)";
// Positive control for the dominance leg: run nested DEEPER (a try block) with the launch at the
// ancestor scope is the LIVE shape and must stay green — prefix, not equality.
export const DRV_TRY_RUN =
  DRV_OPEN +
  "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)" +
  "\n try {" +
  "\n     roundRun.run(drive, self, turnJob)" +
  "\n } finally { capPoller.cancel() }";
// DR-35f: codex's fourth reproduced false green — an unconditional anchored val whose TARGET is
// conditional: the poller runs on every path but cancels a throwaway Job() on non-ping paths.
export const DRV_CONDITIONAL_TARGET =
  DRV_OPEN +
  "\n val capPoller = drive.watchdog.launchTotalCap(self, if (pingClient) turnJob else Job())" +
  "\n roundRun.run(drive, self, turnJob)";
// DR-35e: codex's third reproduced false green — a compilable raw string carries the anchored
// launch line while the live poller is an inert Job(). Every scan must run masked.
export const DRV_STRING_DECOY =
  DRV_OPEN +
  '\n val fake = """' +
  "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)" +
  '\n """' +
  "\n val capPoller = Job()" +
  "\n roundRun.run(drive, self, turnJob)";

function selftest(): number {
  const fails: string[] = [];
  if (detect(WD_OPEN, DRV_OPEN).length === 0) {
    fails.push("open gap (no launchTotalCap anywhere) must be RED");
  }
  if (detect(WD_CLOSED, DRV_CLOSED).length > 0) {
    fails.push(`closed gap must be GREEN, got ${pyRepr(detect(WD_CLOSED, DRV_CLOSED))}`);
  }
  if (detect(WD_CLOSED, DRV_OPEN).length === 0) {
    fails.push("launchTotalCap declared but never launched by TurnDriver must be RED");
  }
  if (detect(WD_CLOSED, DRV_LATE).length === 0) {
    fails.push("launchTotalCap AFTER roundRun.run (placement half-fix) must be RED");
  }
  if (detect(WD_CLOSED, DRV_NO_RUN).length === 0) {
    fails.push("a drive without roundRun.run (shape drift) must be RED, refusing vacuous pass");
  }
  if (detect(WD_CLOSED, DRV_CONDITIONAL).length === 0) {
    fails.push(
      "CONDITIONAL cap arming (if (pingClient) launchTotalCap(...) else Job()) must " +
        "be RED — lexical order alone is not unconditional arming (DR-35c)",
    );
  }
  if (detect(WD_CLOSED, DRV_DECOY).length === 0) {
    fails.push(
      "a compliant decoy beside a conditional real site must be RED — exactly one " +
        "unconditional call site (DR-35c)",
    );
  }
  if (detect(WD_CLOSED, DRV_NESTED).length === 0) {
    fails.push(
      "a multi-line nested conditional (line-anchored val inside the if branch) " +
        "must be RED — scope dominance, not line shape (DR-35d)",
    );
  }
  if (detect(WD_CLOSED, DRV_STRING_DECOY).length === 0) {
    fails.push(
      "a raw-string decoy carrying the anchored launch line beside an inert Job() " +
        "poller must be RED — scans run on masked text (DR-35e)",
    );
  }
  if (detect(WD_CLOSED, DRV_CONDITIONAL_TARGET).length === 0) {
    fails.push(
      "an unconditional launch whose TARGET is conditional (self, if (pingClient) " +
        "turnJob else Job()) must be RED — the argument list is pinned (DR-35f)",
    );
  }
  if (detect(WD_CLOSED, DRV_TRY_RUN).length > 0) {
    fails.push(
      "the live shape (launch at ancestor scope, run inside try) must be GREEN — " +
        `dominance is prefix, not equality; got ${pyRepr(detect(WD_CLOSED, DRV_TRY_RUN))}`,
    );
  }
  if (detect(null, DRV_CLOSED).length === 0 || detect(WD_CLOSED, null).length === 0) {
    fails.push("missing source files must be RED, never a vacuous pass");
  }
  if (detect("class TurnWatchdog {}", DRV_CLOSED).length === 0) {
    fails.push("a Watchdog.kt without launchIn (shape change) must be RED, refusing vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("NF-03 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "NF-03 SELFTEST OK — red on missing poller, missing launch site, launch-after-rounds " +
      "placement, conditional/decoyed/branch-nested/conditional-target arming, missing " +
      "roundRun shape, missing files, and launchIn shape change; green only when exactly one " +
      "unconditional `launchTotalCap(self, turnJob)` scope-dominates and precedes the rounds " +
      "AND idle keeps launchIn\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(WATCHDOG), read(DRIVER));
  if (problems.length > 0) {
    process.stdout.write("NF-03 WALL RED — the whole-turn wall clock is unenforced outside an open stream:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "NF-03 WALL GREEN: totalCap is armed for the whole turn; idle tiers keep their stream-scoped poller.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
