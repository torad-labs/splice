#!/usr/bin/env bun
/** Red-green proof for checks/no-python.ts, out of tree.
 *
 *  Every arm asserts its SETUP before it grades: the first version of this file
 *  reported "stale entry -> GREEN, expected red" when `git rm` had actually
 *  refused and the mutation never happened. An arm that grades a mutation it
 *  did not make is the failure this whole wall family exists to catch. */
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, copyFileSync, existsSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const LIST = "checks/config/python-burndown.json";
let failures = 0;

function git(cwd: string, ...args: string[]) {
  const r = spawnSync("git", ["-C", cwd, ...args], { encoding: "utf8" });
  return { rc: r.status ?? 1, out: (r.stdout || "").trim(), err: (r.stderr || "").trim() };
}
function tracked(cwd: string): string[] {
  return git(cwd, "ls-files", "*.py").out.split("\n").map((s) => s.trim()).filter(Boolean);
}
function untrackedPy(cwd: string): string[] {
  return git(cwd, "ls-files", "--others", "--exclude-standard", "*.py").out.split("\n").map((s) => s.trim()).filter(Boolean);
}
function run(cwd: string) {
  const r = spawnSync("bun", ["checks/no-python.ts"], { cwd, encoding: "utf8" });
  return { rc: r.status ?? 1, out: (r.stdout || "") + (r.stderr || "") };
}
function arm(label: string, expect: "red" | "green", build: (root: string) => void, setupOk: (root: string) => boolean) {
  const root = mkdtempSync(join(tmpdir(), "nopy-"));
  try {
    mkdirSync(join(root, "checks", "config"), { recursive: true });
    copyFileSync("checks/no-python.ts", join(root, "checks", "no-python.ts"));
    git(root, "init", "-q"); git(root, "config", "user.email", "t@t"); git(root, "config", "user.name", "t");
    build(root);
    if (!setupOk(root)) { console.log(`  FAIL  ${label} — SETUP did not take; arm graded nothing`); failures++; return; }
    const { rc, out } = run(root);
    const red = rc !== 0;
    if ((expect === "red") === red) console.log(`  PASS  ${label} -> ${red ? `RED (${rc})` : "GREEN"}`);
    else { console.log(`  FAIL  ${label} -> ${red ? `RED (${rc})` : "GREEN"}, expected ${expect}\n${out}`); failures++; }
  } finally { rmSync(root, { recursive: true, force: true }); }
}
const list = (files: string[], invokers: string[] = []) =>
  JSON.stringify({ recorded: "2026-09-18", law: "selftest", files, invokers });

arm("recorded set matches", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).join() === "a.py");

arm("a NEW .py appears", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
  writeFileSync(join(r, "sneaky.py"), "print('hi')\n");
  git(r, "add", "-A");
}, (r) => tracked(r).includes("sneaky.py"));

arm("an EMPTY new .py", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
  writeFileSync(join(r, "empty.py"), "");
  git(r, "add", "-A");
}, (r) => tracked(r).includes("empty.py"));

arm("a listed file was converted", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n"); writeFileSync(join(r, "b.py"), "x\n");
  writeFileSync(join(r, LIST), list(["a.py", "b.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
  git(r, "rm", "-qf", "b.py");
}, (r) => !tracked(r).includes("b.py"));

// ── the second census: Python the first one cannot see ────────────────────────
// Added after the file census read a triumphant 90 on the real tree while 29 .sh
// files shelled into python3 167 times. Every .py could be deleted, the wall would
// report zero, and the build would still run Python out of heredocs.

arm("a .sh that shells into python3, unlisted", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "run.sh"), "#!/bin/sh\npython3 -c 'print(1)'\n");
  writeFileSync(join(r, LIST), list(["a.py"], []));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).includes("a.py"));

// The half nobody notices: prose goes stale where an invocation fails loudly.
arm("a README that TEACHES python3, unlisted", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "README.md"), "Run the check with `python3 checks/foo.py`.\n");
  writeFileSync(join(r, LIST), list(["a.py"], []));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).includes("a.py"));

arm("a listed invoker that was already converted", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "run.sh"), "#!/bin/sh\nbun x.ts\n"); // no python any more
  writeFileSync(join(r, LIST), list(["a.py"], ["run.sh"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).includes("a.py"));

arm("both censuses matching their lists", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "run.sh"), "#!/bin/sh\npython3 -c 'print(1)'\n");
  writeFileSync(join(r, LIST), list(["a.py"], ["run.sh"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).includes("a.py"));

// A .py is counted ONCE, by the file census — never double-counted as its own invoker.
arm("a .py whose docstring names python3", "green", (r) => {
  writeFileSync(join(r, "a.py"), '"""run with python3 a.py"""\n');
  writeFileSync(join(r, LIST), list(["a.py"], []));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).includes("a.py"));

// ── the third census: Python that is not in git at all ────────────────────────
// Added after webui/.m1-34.py was found sitting untracked in the worktree twenty
// minutes after this wall landed — 196 lines of Python rewriting six .tsx files,
// and both censuses above read it as a clean tree. Tracked is what SHIPS; it is
// not what a session WROTE, and the drift is written before it is added.

arm("an UNTRACKED scratch .py in the worktree", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
  writeFileSync(join(r, "scratch.py"), "import pathlib\n"); // written, never added
}, (r) => !tracked(r).includes("scratch.py") && untrackedPy(r).includes("scratch.py"));

// The leg must charge the AUTHOR, never the package manager: node_modules carries
// a vendored flatted.py in this repo, and flagging it would make the wall unpassable
// for a reason no session can fix. --exclude-standard is what makes the leg usable,
// so it gets its own arm rather than riding on the red one.
// The setup asserts only that the file is on disk and untracked — asserting it is
// absent from the census would be asserting the very thing this arm grades.
arm("a .gitignore'd vendor .py is NOT charged", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  writeFileSync(join(r, ".gitignore"), "vendor/\n");
  mkdirSync(join(r, "vendor"), { recursive: true });
  writeFileSync(join(r, "vendor", "dep.py"), "x\n");
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => existsSync(join(r, "vendor", "dep.py")) && !tracked(r).includes("vendor/dep.py"));

// ---- THE FOURTH CENSUS: a caller that outlived the file it calls. ----
// The live defect this was written from: gate.sh named a converted script on two lines
// and only one was repointed, so the gate failed at run time while every other census
// reported a clean burn-down.

arm("a .sh calling a script that does NOT exist", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "run.sh"), "bun checks/config/gone.ts check .\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => existsSync(join(r, "run.sh")) && !existsSync(join(r, "checks", "config", "gone.ts")));

arm("a .sh calling a script that DOES exist", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "run.sh"), "bun checks/no-python.ts\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => existsSync(join(r, "checks", "no-python.ts")));

// ---- THE FIFTH CENSUS: a ledger instruction naming a file that is gone. ----
// Graded ONLY on rows that have not run yet. The two green arms below are the boundary,
// and they matter more than the red one: without them the obvious "any missing path is
// bad" implementation passes its red arm and then charges correct ledger authorship.

const ledger = (r: string, id: string, status: string, verify: string) => {
  mkdirSync(join(r, "dev", "campaigns"), { recursive: true });
  writeFileSync(join(r, "dev", "campaigns", "c.toml"), `[[items]]\nid = "${id}"\nstatus = "${status}"\nverify = "${verify}"\n`);
};

arm("a TODO row whose verify names a missing .py", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  ledger(r, "T-1", "todo", "python3 checks/gone.py");
  writeFileSync(join(r, LIST), list(["a.py"], ["dev/campaigns/c.toml"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => !existsSync(join(r, "checks", "gone.py")));

arm("a TODO row naming a .ts it will CREATE", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  ledger(r, "T-2", "todo", "bun checks/not-yet-written.ts");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => !existsSync(join(r, "checks", "not-yet-written.ts")));

arm("a VERIFIED row's verify is history, not an instruction", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  ledger(r, "T-3", "verified", "python3 checks/long-since-converted.py");
  writeFileSync(join(r, LIST), list(["a.py"], ["dev/campaigns/c.toml"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => !existsSync(join(r, "checks", "long-since-converted.py")));

// A .py deleted in the worktree but NOT yet staged is still `git ls-files` tracked. Before
// the existsSync filter this read as a matching denominator and the wall was green on a file
// that did not exist — found by splice-builder2, which refused to trust a green whose
// mechanism it could not explain.
arm("a tracked .py DELETED but not yet staged", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
  rmSync(join(r, "a.py"));
}, (r) => !existsSync(join(r, "a.py")) && tracked(r).includes("a.py"));

// A verify names a RUNTIME as well as a path. Updating only the path leaves a caller that
// looks migrated and cannot execute — builder2's own slip inside the granted caller path.
arm("a TODO row naming the WRONG runtime for the extension", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "checks", "w.ts"), "//\n");
  ledger(r, "T-4", "todo", "python3 checks/w.ts");
  writeFileSync(join(r, LIST), list(["a.py"], ["dev/campaigns/c.toml"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => existsSync(join(r, "checks", "w.ts")));

// The fourth caller surface: a live row whose files= fence names a .py that is gone. Reported by
// splice-builder2 from a hole in the census itself — a wall registered against two items is named
// in BOTH their files= lists, and updating one leaves the other stale.
arm("a TODO row whose files= names a missing .py", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  mkdirSync(join(r, "dev", "campaigns"), { recursive: true });
  writeFileSync(join(r, "dev", "campaigns", "c.toml"), `[[items]]\nid = "T-5"\nstatus = "todo"\nfiles = ["checks/vanished.py"]\n`);
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => !existsSync(join(r, "checks", "vanished.py")));

arm("a TODO row whose files= is a GLOB", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  mkdirSync(join(r, "dev", "campaigns"), { recursive: true });
  writeFileSync(join(r, "dev", "campaigns", "c.toml"), `[[items]]\nid = "T-6"\nstatus = "todo"\nfiles = ["checks/*.py"]\n`);
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
  // The setup that must take is the GLOB being in the ledger — `checks/` itself always exists here,
  // because the fixture creates it, so asserting on that graded nothing.
}, (r) => readFileSync(join(r, "dev", "campaigns", "c.toml"), "utf8").includes('files = ["checks/*.py"]'));

arm("an unparseable burn-down list", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), "not json");
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).includes("a.py"));

console.log(failures ? `\nFAIL: no-python selftest — ${failures} arm(s)` : "\nOK: no-python selftest — every arm graded a setup it verified");
process.exit(failures ? 1 : 0);
