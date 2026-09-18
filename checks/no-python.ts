#!/usr/bin/env bun
/**
 * THE RULE: this repo has no Python. Tooling is bun/TypeScript.
 *
 * WHY THIS FILE EXISTS AND THE RULE ALONE DID NOT WORK. The rule was stated,
 * repeatedly, and drifted every time — because nothing failed when a session
 * added another .py, and because the tree taught the opposite of the rule. On
 * 2026-09-18 it held 96 Python files and 35,166 Python lines against ZERO .ts
 * outside webui/. A session that reads "match the surrounding style" and then
 * looks at the surrounding style learns Python. The clearest evidence of the
 * drift WAS dev/web-console/idle-watch.py, whose own docstring recorded that it
 * was "vendored from grailseeker-bot .dev/campaigns/idle-watch.ts ... ported to
 * python" — a TypeScript original, deliberately converted the wrong way.
 *
 * THAT FILE IS FIXED, AND THIS PARAGRAPH STAYS IN THE PAST TENSE ON PURPOSE. M1-88 ported it
 * back to dev/web-console/idle-watch.ts under bun on 2026-09-18, and its burndown line is burned
 * off with it — a file that no longer exists cannot hold an allowlist entry, which is the
 * burn-down burning down rather than the list being weakened. The scar is kept because the wall
 * is the reason it got fixed: the instance sat in the same directory as the rule that names it,
 * and it took a row to remove it. A wall that erases its own exhibits the moment they are
 * repaired cannot show the next session what the drift looks like.
 *
 * So the rule is a WALL now, in the idiom the rest of checks/ already uses:
 *
 *   · A NEW .py fails. Any tracked Python file not in the allowlist is a hard
 *     error naming the file. This is the leg that stops the drift.
 *   · A STALE entry fails. An allowlist line whose file is gone or converted is
 *     a hard error, so the list can only shrink and never silently holds room
 *     for a file to come back into.
 *   · AN UNTRACKED .py fails, with no allowlist at all. Both legs above read
 *     `git ls-files` and are therefore blind to the scratch script that has not
 *     been added yet — which is the state every tracked .py passed through on
 *     its way in. This is the leg that catches the drift one move earlier.
 *   · IT CANNOT BE SATISFIED BY WEAKENING. Adding to the allowlist to make the
 *     gate pass is the violation, not the remedy — the allowlist is a dated
 *     burn-down of what already existed, not a permission slip.
 *
 * The denominator comes from `git ls-files`, never from the allowlist itself
 * (campaign law 24): a list checked against itself cannot fail for anything
 * absent from it.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";

const ALLOW = "checks/config/python-burndown.json";

type Burndown = { recorded: string; law: string; files: string[]; invokers?: string[] };

function gitLs(...pathspec: string[]): string[] {
  const r = spawnSync("git", ["ls-files", ...pathspec], { encoding: "utf8" });
  if (r.status !== 0) {
    console.error(`no-python: git ls-files failed (${r.stderr.trim()}) — refusing to report a pass for a census that did not run`);
    process.exit(2);
  }
  return r.stdout.split("\n").map((s) => s.trim()).filter(Boolean).sort();
}

/** Tracked .py files THAT ACTUALLY EXIST.
 *
 *  The existsSync filter is not belt-and-braces; without it this census reports a
 *  green that is structurally the two-lists-agreeing failure. Found by splice-builder2
 *  on 2026-09-18, which refused to call its own conversion clean while it could not
 *  explain the mechanism: it had deleted inf_02_every_law_walled.py, the burn-down
 *  still carried the line, and this wall read a matching 87 / 87.
 *
 *  `git ls-files` enumerates what the INDEX tracks, and a file deleted in the worktree
 *  but not yet staged is still tracked — `git status` calls it ` D`. So the deleted file
 *  stayed in `measured`, matched its burn-down line, and the stale arm had nothing to
 *  report. The wall was comparing the index against a list while the question it claims
 *  to answer is about the FILESYSTEM.
 *
 *  Filtering to what exists makes the deletion visible the moment it happens rather than
 *  when it is staged: the file leaves `measured`, its burn-down line becomes STALE, and
 *  the wall says "remove the line" by name. That is the instruction a conversion needs
 *  mid-flight, which is exactly when the old shape was silent.
 *
 *  The other three censuses were never exposed to this: invokers and the two caller
 *  censuses all readFileSync and a missing file simply drops out. */
function tracked(): string[] {
  return gitLs("*.py").filter((f) => existsSync(f));
}

/** THE SECOND CENSUS, and the wall was a lie without it.
 *
 *  Counting files named `.py` is not counting PYTHON. Measured 2026-09-18, after
 *  five gate legs had been converted and the first census read a triumphant 90:
 *  29 tracked .sh files invoked python3 a total of 167 times, six package.json
 *  scripts did, and three .mjs files did. Every .py in the repo could have been
 *  deleted, this wall would have reported ZERO, and the build would still have
 *  shelled into Python 167 times from inside heredocs it could not see.
 *
 *  That is this campaign's own law pointed at its own instrument: the bug is in
 *  the shape of the check, not the shape of the fix. The denominator has to be
 *  "files that RUN python", enumerated from their contents, not "files whose name
 *  ends in .py".
 *
 *  A mention counts. A comment or a README saying `python3 checks/foo.py` is a
 *  live instruction to the next session to write more Python, and prose goes
 *  quietly stale where an invocation fails loudly — which is the half nobody
 *  notices. `.py` files are excluded only because the first census already owns
 *  them; the burn-down deletes them wholesale. */
/** EXCLUDED WITH A WRITTEN REASON, which is a disposition and not a hole (law 24).
 *  These three files exist to TALK about Python: the wall, its selftest, and the
 *  burn-down list. Their prose necessarily contains the word, and counting them
 *  would make the wall permanently report itself. Nothing else is exempt — a file
 *  that merely explains a python command is drift and IS counted, because prose is
 *  what teaches the next session which language this repo writes tooling in. */
const SELF = new Set([ALLOW, "checks/no-python.ts", "checks/no-python-selftest.ts"]);

/** Does this text RUN or NAME python?
 *
 *  The wall's own NAME is not a python reference, and on 2026-09-18 that distinction
 *  was the difference between a green gate and a red one. webui/.impeccable/review/
 *  ink/sweep-d7.mjs is the PORT AWAY FROM PYTHON — its header explains that the repo
 *  runs no Python and that the prose teaching it goes stale in silence, which is the
 *  rule stated correctly — and the only lowercase `python` anywhere in it is the
 *  phrase "the no-python rule". A hyphen is not a word character, so `\bpython\b`
 *  matched inside the rule's own name and charged the file as an invoker. Every other
 *  mention in it is capitalized prose about the language's history and never matched.
 *
 *  So the wall was failing the act of COMPLYING with it, which is worse than a plain
 *  false positive: the remedy it suggested was to un-write the sentence explaining the
 *  port. Fix-the-gate, not reword-the-file.
 *
 *  THIS IS A NARROWING, SO IT IS MEASURED RATHER THAN ARGUED — a wall that quietly
 *  stops charging real invokers is the exact failure this whole campaign is named for.
 *  Across all 79 charged files, excluding the literal `no-python` drops EXACTLY ONE:
 *  sweep-d7.mjs, the false positive. The other 78 keep their charge, because every one
 *  of them names python for a reason that survives deleting the rule's name from the
 *  text. It cannot become a dodge either: a file that actually invokes `python3` still
 *  matches on that token no matter how often it also writes "no-python". */
function namesPython(text: string): boolean {
  return /\bpython3?\b/.test(text.replaceAll("no-python", ""));
}

function invokers(): string[] {
  return gitLs()
    .filter((f) => !f.endsWith(".py") && !SELF.has(f))
    .filter((f) => {
      try {
        return namesPython(readFileSync(f, "utf8"));
      } catch {
        return false; // a binary or unreadable blob invokes nothing
      }
    })
    .sort();
}

/** THE THIRD CENSUS, and the two above are structurally blind to it.
 *
 *  `git ls-files` enumerates what the repo TRACKS. That is the right denominator
 *  for what SHIPS, and it is exactly why it cannot see the file that starts the
 *  drift — Python does not arrive tracked. It arrives as a scratch script someone
 *  writes in the worktree, runs once, and adds later because it is already there.
 *
 *  Measured 2026-09-18, twenty minutes after this wall landed and while the first
 *  census read a clean 90: webui/.m1-34.py, 196 untracked lines of Python
 *  rewriting six .tsx files by string substitution. Invisible to both censuses
 *  above, and one `git add` away from being tracked.
 *
 *  THERE IS NO ALLOWLIST FOR THIS LEG, deliberately. The burn-down list records
 *  Python that already existed when the rule landed; an untracked file is by
 *  definition newer than the list, so every entry would be an exception granted
 *  after the fact — the precise shape the other two legs already refuse. The
 *  remedy is never a new line here: move the script to a scratch directory
 *  OUTSIDE the worktree, which is where a throwaway belongs whatever its language.
 *
 *  --exclude-standard is load-bearing, not tidiness: it drops .gitignore'd trees,
 *  so a vendored dependency's Python (node_modules/flatted/python/flatted.py, the
 *  one such file here) is not charged to the author. This leg measures what a
 *  session WROTE, never what a package manager unpacked. */
function untracked(): string[] {
  return gitLs("--others", "--exclude-standard", "*.py");
}

/** THE FOURTH CENSUS: a CALLER that outlived the file it calls.
 *
 *  This leg exists because the wall was green on 2026-09-18 while the gate of
 *  record was red, and it was green for the most ordinary reason there is.
 *  checks/config/shared-quirks-no-vendor-defaults was converted to .ts; the
 *  burn-down line was removed, the .py was deleted, the .ts ran its check, its
 *  report and its selftest byte-identically to the .py on both streams. Every
 *  arm above passed, correctly. But checks/gate.sh named that script on TWO
 *  lines — a `check .` leg and a `--selftest` leg — and only the first was
 *  converted. The second still read `python3 ...py --selftest`, so the gate's
 *  own invocation failed with "No such file or directory", exit 2.
 *
 *  Both the builder and the orchestrator had called the row green. Neither was
 *  careless: each had verified the FILES against each other, and neither had
 *  verified the WIRING. That is the whole family this campaign is about — a
 *  check that returns green while measuring the wrong thing — so the remedy is
 *  a census, not a reminder.
 *
 *  THE STALE ARM ABOVE IS THE SAME HALF-MIGRATION SEEN FROM THE OTHER SIDE. It
 *  catches a burn-down LINE that outlived its file. This catches a CALL SITE
 *  that outlived its file. A conversion has to satisfy both to land, which is
 *  what makes "I updated the one invocation I remembered" fail loudly instead
 *  of silently.
 *
 *  NO ALLOWLIST, deliberately: a caller naming a file that does not exist is
 *  never a state worth recording, only one worth fixing.
 *
 *  SCOPE AND ITS ONE ASSUMPTION, measured rather than asserted. It reads every
 *  tracked .sh, .mjs and package.json — 45 literal invocations today, 40 of
 *  them in shell, 5 in package.json, 0 in .mjs — and resolves each path from
 *  the REPO ROOT, which is the house convention and how the gate runs. A script
 *  that invoked a sibling by a path relative to its own directory would be a
 *  false positive; there are none today, and the honest reading of a red here
 *  is "go look", not "the path is wrong". Interpolated paths are invisible to
 *  it, which is a limit of the instrument and not a pass. */
const INVOCATION = /(?:python3|bun)\s+([A-Za-z0-9_./-]+\.(?:py|ts))/g;

function dangling(): string[] {
  const out = new Set<string>();
  for (const caller of gitLs("*.sh", "*.mjs", "package.json")) {
    let text: string;
    try {
      text = readFileSync(caller, "utf8");
    } catch {
      continue; // a path git tracks but the worktree lacks is the stale arm's business, not this one
    }
    for (const [, target] of text.matchAll(INVOCATION)) {
      if (!existsSync(target)) out.add(`${caller} -> ${target}`);
    }
  }
  return [...out].sort();
}

/** THE FIFTH CENSUS: a ledger instruction that names a file the burn-down is about to delete.
 *
 *  Asked for by splice-builder2 on 2026-09-18, from a shape it found that the fourth
 *  census cannot see BY CONSTRUCTION. The fourth grades call sites against what exists,
 *  so it catches a caller that is ALREADY broken. builder2 found the form one move
 *  earlier: proxy-hardening.toml's INF-01 carries `verify = "python3 ...inf_01....py"`
 *  while the wall registry already names the .ts. Nothing is broken, because the .py
 *  still exists. It breaks the moment the conversion deletes it — inside a commit about
 *  a different wall, which is the worst place for a failure to first appear.
 *
 *  ONLY ROWS THAT WILL ACTUALLY RUN ARE GRADED, and that boundary is the whole design.
 *  A `verify` on a done/verified row is a HISTORICAL RECORD of the gate that ran when
 *  the row landed; a todo/in_flight row's `verify` is an INSTRUCTION that has not run
 *  yet. Measured 2026-09-18 across every tracked campaign ledger: 12 verify strings name
 *  a .py that no longer exists, and all 12 sit on done/verified rows — DR-187, V4-26,
 *  V4-29, V4-31, V4-92, V4-122 and the rest are records of conversions that have already
 *  happened. Charging those would demand rewriting the record of what was run, which is
 *  falsifying history to make a wall green, so they are deliberately out of scope. On
 *  rows that will run, the count today is ZERO — and the three todo rows INF-01, INF-02
 *  and INF-04 are each exactly one deletion away from entering it.
 *
 *  It reads the ledgers as TEXT rather than through the CLI on purpose: the CLI is the
 *  only WRITE channel (campaign law), and a wall that had to boot the write path to take
 *  a reading would be a checker with a side effect. */
function staleVerifies(): string[] {
  const out: string[] = [];
  for (const ledger of gitLs("dev/campaigns/*.toml")) {
    let text: string;
    try {
      text = readFileSync(ledger, "utf8");
    } catch {
      continue;
    }
    for (const block of text.split(/^\[\[items\]\]$/m).slice(1)) {
      const status = block.match(/^status\s*=\s*"([^"]+)"/m)?.[1] ?? "";
      if (status !== "todo" && status !== "in_flight") continue;
      const id = block.match(/^id\s*=\s*"([^"]+)"/m)?.[1] ?? "(unidentified row)";
      const quoted = block.match(/^verify\s*=\s*(?:"""([\s\S]*?)"""|"((?:[^"\\]|\\.)*)")/m);
      const verify = quoted ? (quoted[1] ?? quoted[2] ?? "") : "";
      // .py ONLY, and the asymmetry is load-bearing rather than lazy. A live row may
      // legitimately name a .ts that does not exist yet, because the row is what CREATES
      // it — that is declare-then-earn working, not a dangling reference. This census
      // charged exactly three such forward declarations the first time it ran (V4-143 ->
      // manifest.ts, V4-144 -> test_orchestrator.ts, M2-08 -> tests/teams.test.ts) and
      // every one was correct ledger authorship. A missing .py can never be that: the
      // burn-down only shrinks, so no row is ever permitted to bring a new .py into
      // existence, and a live verify naming one that is gone is unambiguously stale.
      for (const target of new Set(verify.match(/[A-Za-z0-9_./-]+\.py\b/g) ?? [])) {
        if (!existsSync(target)) out.push(`${ledger} ${id} [${status}] -> ${target} (file is gone)`);
      }
      // A verify names a RUNTIME as well as a path, and a conversion that updates only the path
      // leaves a caller that looks migrated and cannot execute. Reported by splice-builder2 from
      // its OWN slip inside the granted caller path: its first edit-verify wrote
      // `python3 ...inf_02_every_law_walled.ts` — correct file, wrong interpreter. The missing-file
      // arm above waves that through, because the file it names genuinely exists.
      //
      // Scoped to verify= fields rather than ledger text for a measured reason: across every
      // tracked caller surface the raw-text form finds five mismatches and only one is real —
      // the others are NOTES quoting a command, including builder2's own note recording this very
      // mistake. A census that charges the write-up of a defect as the defect is not a census.
      for (const [, runtime, target] of verify.matchAll(/(python3?|bun)\s+([A-Za-z0-9_./-]+\.(?:py|ts))/g)) {
        const wrong = runtime.startsWith("python") ? target.endsWith(".ts") : target.endsWith(".py");
        if (wrong) out.push(`${ledger} ${id} [${status}] -> ${runtime} ${target} (wrong runtime for that extension)`);
      }
      // THE FOURTH CALLER SURFACE, reported by splice-builder2 on 2026-09-18 from a hole it found
      // in this very census and did not exploit. A wall that collapses two items is registered
      // TWICE, and the items owning it name it in `files =` as well as in the registry — so a
      // conversion has up to four caller surfaces (registry rows, verify=, files=, the burn-down)
      // and this arm was grading only two of them. It read 0 while two files= entries were already
      // stale.
      //
      // LIVE ROWS AND LITERAL PATHS ONLY, both boundaries measured rather than chosen. Across every
      // tracked ledger: 23 stale .py entries sit in files= lists, and ALL 23 are on done/verified
      // rows, where files= is the record of what the row touched — CX-07, which raised this, is
      // `verified`. On rows that will still run: zero. And 456 of the entries are GLOBS, which
      // cannot be existence-checked at all (a glob matching nothing is a legitimate fence for work
      // not yet done), so they are skipped rather than guessed at.
      const files = block.match(/^files\s*=\s*\[([\s\S]*?)\]/m);
      for (const [, entry] of (files?.[1] ?? "").matchAll(/"([^"]+)"/g)) {
        if (entry.includes("*") || !entry.endsWith(".py") || existsSync(entry)) continue;
        out.push(`${ledger} ${id} [${status}] -> ${entry} (files= fence names a file that is gone)`);
      }
    }
  }
  return out.sort();
}

function burndown(): Burndown {
  if (!existsSync(ALLOW)) {
    console.error(`no-python: ${ALLOW} missing — the wall has no burn-down list to grade against`);
    process.exit(2);
  }
  try {
    return JSON.parse(readFileSync(ALLOW, "utf8")) as Burndown;
  } catch (e) {
    console.error(`no-python: ${ALLOW} is not valid JSON (${e}) — a list nobody can parse grades nothing`);
    process.exit(2);
  }
}

/** One census graded against its own list, both directions. Returns the problems. */
function grade(
  label: string,
  measured: string[],
  allowed: string[],
  newHelp: string,
): string[] {
  const set = new Set(allowed);
  const added = measured.filter((f) => !set.has(f));
  const stale = allowed.filter((f) => !measured.includes(f)).sort();
  const out: string[] = [];
  if (added.length) {
    out.push(`NEW PYTHON (${label}): ${added.length} file(s) not in the burn-down list. ${newHelp}\n    ` + added.join("\n    "));
  }
  if (stale.length) {
    out.push(
      `STALE (${label}): ${stale.length} burn-down entry(ies) name a file that no longer offends — gone, ` +
        `or already converted. Remove the line — a list held above the measured surface is unearned room ` +
        `for Python to come back into:\n    ` + stale.join("\n    "),
    );
  }
  return out;
}

function main(): number {
  const measured = tracked();
  const list = burndown();
  const problems: string[] = [];

  const runners = invokers();
  const allowedInvokers = list.invokers ?? [];
  const scratch = untracked();
  const broken = dangling();
  const willRun = staleVerifies();

  console.log(`NO-PYTHON WALL — burn-down recorded ${list.recorded || "(none)"}`);
  console.log(`  tracked .py files                  measured ${String(measured.length).padStart(4)}   allowed ${String(list.files.length).padStart(4)}   [GATED]`);
  console.log(`  files that RUN or name python      measured ${String(runners.length).padStart(4)}   allowed ${String(allowedInvokers.length).padStart(4)}   [GATED]`);
  console.log(`  UNTRACKED .py in the worktree      measured ${String(scratch.length).padStart(4)}   allowed ${String(0).padStart(4)}   [GATED]`);
  console.log(`  call sites naming a missing file   measured ${String(broken.length).padStart(4)}   allowed ${String(0).padStart(4)}   [GATED]`);
  console.log(`  live ledger verify= gone missing   measured ${String(willRun.length).padStart(4)}   allowed ${String(0).padStart(4)}   [GATED]`);

  if (willRun.length) {
    problems.push(
      `LEDGER VERIFY NAMES A MISSING FILE: ${willRun.length} row(s) that have NOT run yet carry a verify command ` +
        `naming a script that does not exist. Unlike a done/verified row — whose verify is a record of what ran, ` +
        `and is deliberately not graded here — these are instructions, and each one will fail the moment someone ` +
        `runs the row. Repoint it with the manifest CLI's edit-verify, in the SAME commit that converted the ` +
        `script, because the gap between the two is where this defect lives:\n    ` + willRun.join("\n    "),
    );
  }

  if (broken.length) {
    problems.push(
      `DANGLING INVOCATION: ${broken.length} call site(s) name a script that does not exist. A conversion ` +
        `deleted the file and left a caller pointing at it, so the leg fails at run time with "No such file or ` +
        `directory" while every census above reports a clean burn-down. Repoint the call at the .ts — and grep ` +
        `for the stem before you report, because a converted script usually has more than one call site and the ` +
        `one you remember is not the one that breaks:\n    ` + broken.join("\n    "),
    );
  }

  if (scratch.length) {
    problems.push(
      `UNTRACKED PYTHON: ${scratch.length} file(s) written into the worktree but never added. The two censuses ` +
        `above read \`git ls-files\` and cannot see these, which is how every tracked .py in the burn-down got ` +
        `here in the first place. Move it to a scratch directory OUTSIDE the worktree — a throwaway does not ` +
        `belong in the tree whatever its language — or write it as .ts if it is going to be kept. Adding it to ` +
        `${ALLOW} is not available: that list is a dated record of what already existed, and this file is newer ` +
        `than the list by definition:\n    ` + scratch.join("\n    "),
    );
  }

  problems.push(
    ...grade(
      "file",
      measured,
      list.files,
      `This repo is bun/TypeScript; write it as .ts and run it with bun. Do NOT add the file to ${ALLOW} — that ` +
        `list is a dated record of what already existed, and growing it is the violation this wall exists to catch.`,
    ),
    ...grade(
      "invocation",
      runners,
      allowedInvokers,
      `A file that shells into python3, or documents a python3 command, is Python this repo still runs and still ` +
        `teaches. Convert the call to bun; if it is prose, update the prose. Do NOT add a line to ${ALLOW}.`,
    ),
  );

  if (problems.length) {
    console.error(`\nFAIL: no-python wall — ${problems.length} problem(s):`);
    for (const p of problems) console.error("  x " + p);
    return 1;
  }
  console.log(
    `\nOK: no-python wall holds — ${measured.length} tracked .py file(s) and ${runners.length} file(s) that run or ` +
      `name python, both exactly the ${list.recorded} burn-down, nothing listed has already been converted, and no ` +
      `untracked .py is sitting in the worktree waiting to be added`,
  );
  return 0;
}

process.exit(main());
