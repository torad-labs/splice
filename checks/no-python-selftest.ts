#!/usr/bin/env bun
/** Red-green proof for checks/no-python.ts, out of tree.
 *
 *  Every arm asserts its SETUP before it grades: the first version of this file
 *  reported "stale entry -> GREEN, expected red" when `git rm` had actually
 *  refused and the mutation never happened. An arm that grades a mutation it
 *  did not make is the failure this whole wall family exists to catch. */
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, copyFileSync } from "node:fs";
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

arm("an unparseable burn-down list", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), "not json");
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).includes("a.py"));

console.log(failures ? `\nFAIL: no-python selftest — ${failures} arm(s)` : "\nOK: no-python selftest — every arm graded a setup it verified");
process.exit(failures ? 1 : 0);
