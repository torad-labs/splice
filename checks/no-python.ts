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
 * drift is .dev/web-console/idle-watch.py, whose own docstring records that it
 * was "vendored from grailseeker-bot .dev/campaigns/idle-watch.ts ... ported to
 * python" — a TypeScript original, deliberately converted the wrong way.
 *
 * So the rule is a WALL now, in the idiom the rest of checks/ already uses:
 *
 *   · A NEW .py fails. Any tracked Python file not in the allowlist is a hard
 *     error naming the file. This is the leg that stops the drift.
 *   · A STALE entry fails. An allowlist line whose file is gone or converted is
 *     a hard error, so the list can only shrink and never silently holds room
 *     for a file to come back into.
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

function tracked(): string[] {
  return gitLs("*.py");
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

function invokers(): string[] {
  return gitLs()
    .filter((f) => !f.endsWith(".py") && !SELF.has(f))
    .filter((f) => {
      try {
        return /\bpython3?\b/.test(readFileSync(f, "utf8"));
      } catch {
        return false; // a binary or unreadable blob invokes nothing
      }
    })
    .sort();
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

  console.log(`NO-PYTHON WALL — burn-down recorded ${list.recorded || "(none)"}`);
  console.log(`  tracked .py files                  measured ${String(measured.length).padStart(4)}   allowed ${String(list.files.length).padStart(4)}   [GATED]`);
  console.log(`  files that RUN or name python      measured ${String(runners.length).padStart(4)}   allowed ${String(allowedInvokers.length).padStart(4)}   [GATED]`);

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
      `name python, both exactly the ${list.recorded} burn-down, and nothing listed has already been converted`,
  );
  return 0;
}

process.exit(main());
