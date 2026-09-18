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
 * drift is dev/web-console/idle-watch.py, whose own docstring records that it
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

type Burndown = { recorded: string; law: string; files: string[] };

function tracked(): string[] {
  const r = spawnSync("git", ["ls-files", "*.py"], { encoding: "utf8" });
  if (r.status !== 0) {
    console.error(`no-python: git ls-files failed (${r.stderr.trim()}) — refusing to report a pass for a census that did not run`);
    process.exit(2);
  }
  return r.stdout.split("\n").map((s) => s.trim()).filter(Boolean).sort();
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

function main(): number {
  const measured = tracked();
  const list = burndown();
  const allowed = new Set(list.files);
  const problems: string[] = [];

  const added = measured.filter((f) => !allowed.has(f));
  const stale = list.files.filter((f) => !measured.includes(f)).sort();

  console.log(`NO-PYTHON WALL — burn-down recorded ${list.recorded || "(none)"}`);
  console.log(`  tracked .py files                  measured ${String(measured.length).padStart(4)}   allowed ${String(allowed.size).padStart(4)}   [GATED]`);

  if (added.length) {
    problems.push(
      `NEW PYTHON: ${added.length} tracked .py file(s) are not in the burn-down list. This repo is bun/TypeScript; ` +
        `write it as .ts and run it with bun. Do NOT add the file to ${ALLOW} — that list is a dated record of what ` +
        `already existed, and growing it is the violation this wall exists to catch:\n    ` + added.join("\n    "),
    );
  }
  if (stale.length) {
    problems.push(
      `STALE: ${stale.length} burn-down entry(ies) name a file that is gone or already converted. Remove the line — ` +
        `a list held above the measured surface is unearned room for Python to come back into:\n    ` + stale.join("\n    "),
    );
  }

  if (problems.length) {
    console.error(`\nFAIL: no-python wall — ${problems.length} problem(s):`);
    for (const p of problems) console.error("  x " + p);
    return 1;
  }
  console.log(
    `\nOK: no-python wall holds — the ${measured.length} tracked Python file(s) are exactly the ${list.recorded} ` +
      `burn-down, and nothing listed there has already been converted`,
  );
  return 0;
}

process.exit(main());
