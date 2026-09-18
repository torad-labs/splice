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
  writeFileSync(join(r, LIST), list(["a.py"], [".dev/campaigns/c.toml"]));
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
  writeFileSync(join(r, LIST), list(["a.py"], [".dev/campaigns/c.toml"]));
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
  writeFileSync(join(r, LIST), list(["a.py"], [".dev/campaigns/c.toml"]));
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

// The fifth surface: a registry row whose wall= names a file that is gone. builder2 deleted a .py
// whose law_registry row still named it, and every census passed.
arm("a registry wall= naming a missing file", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  mkdirSync(join(r, "walls"), { recursive: true });
  writeFileSync(join(r, "walls", "reg.toml"), `[[law]]\ntag = "L1"\nwall = "walls/gone.py"\n`);
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => !existsSync(join(r, "walls", "gone.py")));

arm("a registry wall= that is EMPTY is not charged", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  mkdirSync(join(r, "walls"), { recursive: true });
  writeFileSync(join(r, "walls", "reg.toml"), `[[law]]\ntag = "L2"\nwall = ""\n`);
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
  // An empty wall= is the registry's own spelling for "no wall yet" and is RED in the campaign
  // gate, not here. Charging it would make this wall duplicate a verdict that already has an owner.
}, (r) => readFileSync(join(r, "walls", "reg.toml"), "utf8").includes('wall = ""'));

arm("an unparseable burn-down list", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), "not json");
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => tracked(r).includes("a.py"));

// THE SIXTH SURFACE: the list itself. Found by walking the three moves a session makes to get a
// green gate — write a .py (red), git add it (red), ADD IT TO THE BURN-DOWN (green until now).
// Two censuses print "do NOT add the file to the burn-down" in their own failure text, and nothing
// enforced it. These arms need TWO commits, because the baseline is the list as FIRST recorded.
arm("the burn-down GREW after its first commit", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "birth");
  writeFileSync(join(r, "b.py"), "y\n");
  writeFileSync(join(r, LIST), list(["a.py", "b.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "sneak it in");
}, (r) => !git(r, "show", `HEAD~1:${LIST}`).out.includes("b.py") && git(r, "show", `HEAD:${LIST}`).out.includes("b.py"));

arm("the burn-down SHRANK", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "b.py"), "y\n");
  writeFileSync(join(r, LIST), list(["a.py", "b.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "birth");
  rmSync(join(r, "b.py"));
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "converted b");
  // Shrinking is the entire point of the list, so the ratchet must never charge it.
}, (r) => git(r, "show", `HEAD~1:${LIST}`).out.includes("b.py") && !git(r, "show", `HEAD:${LIST}`).out.includes("b.py"));

// The bug the first cut of this census actually had: it took the FILE's first commit as the
// baseline for BOTH keys and charged all 77 invokers as growth, because `invokers` was added days
// after `files`. A ratchet whose baseline predates the thing it measures reports the measurement
// as the violation, so each key is graded from the first commit in which THAT key has content.
arm("a burn-down key that did not exist at the first commit", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "s.sh"), "#!/bin/sh\npython3 a.py\n");
  writeFileSync(join(r, LIST), JSON.stringify({ recorded: "2026-09-18", law: "selftest", files: ["a.py"] }));
  git(r, "add", "-A"); git(r, "commit", "-qm", "birth, files only");
  writeFileSync(join(r, LIST), list(["a.py"], ["s.sh"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "the second census arrives");
}, (r) => !git(r, "show", `HEAD~1:${LIST}`).out.includes("invokers") && git(r, "show", `HEAD:${LIST}`).out.includes("s.sh"));

// ---- THE SEVENTH CENSUS: a live caller running the WRONG runtime for the extension. ----
// The red arm below carries splice-builder2's ACTUAL slip, character for character, quotes and
// all: `python3 "$HERE/mock_chat.ts"`. That spelling is the arm's whole value. The first cut of
// the census matched only an unquoted path, read 0/0 [GATED] on a tree containing that exact
// line, and was caught here rather than in review — a paraphrased arm (`python3 wall.ts`) passes
// against the broken regex and would have shipped a census blind to the only form it has ever
// had to catch. When a defect arrives with a real spelling, the arm gets the real spelling.
arm("a .sh running a .ts through python3, path QUOTED", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "checks", "mock_chat.ts"), "//\n");
  writeFileSync(join(r, "inside.sh"), 'HERE=checks\npython3 "$HERE/mock_chat.ts" --port 8080 &\n');
  writeFileSync(join(r, LIST), list(["a.py"], ["inside.sh"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => readFileSync(join(r, "inside.sh"), "utf8").includes('python3 "$HERE/mock_chat.ts"'));

arm("the same caller with the interpreter fixed", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "checks", "mock_chat.ts"), "//\n");
  writeFileSync(join(r, "inside.sh"), 'HERE=checks\nbun "$HERE/mock_chat.ts" --port 8080 &\n');
  writeFileSync(join(r, LIST), list(["a.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => readFileSync(join(r, "inside.sh"), "utf8").includes('bun "$HERE/mock_chat.ts"'));

arm("a .sh running a .py through bun", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "run.sh"), "bun a.py\n");
  writeFileSync(join(r, LIST), list(["a.py"], ["run.sh"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => existsSync(join(r, "a.py")));

// THE ARM THAT LICENSES THE WHOLE CENSUS. Over raw text the mismatched form finds five hits on
// the caller surface and FOUR are notes quoting a command — which is why the existing
// runtime-vs-extension check was scoped to ledger verify= fields and never widened. Excluding
// comment lines is what takes it from four false positives to zero, so a green here is not a
// nicety: without it this census would charge every seat that documented the defect it fixed.
arm("a COMMENT quoting the wrong-runtime form", "green", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "run.sh"), '# was: python3 "$HERE/mock_chat.ts", fixed in this commit\nbun a.ts\n');
  writeFileSync(join(r, "a.ts"), "//\n");
  writeFileSync(join(r, LIST), list(["a.py"], ["run.sh"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => readFileSync(join(r, "run.sh"), "utf8").startsWith("#"));

// ---- THE THIRD DISPOSITION: a caller excused ONLY because the tool it calls is still Python. ----
// Forced by a real collision: converting the 21 hook scripts to .ts made four of them NEW invokers,
// purely because they name `python3 .dev/campaigns/manifest.py` — a tool V4-143 has not converted
// yet. The .py originals named the same string and were invisible only because the invoker census
// skips .py files. So doing the work reddened the wall.
//
// THE RED ARMS BELOW MATTER MORE THAN THE GREEN ONE. An exclusion mechanism is worth exactly what
// its expiry is worth, and "temporary" allowlists in this repo's history have all been permanent.
// Each of the three ways this entry can stop being true reds the wall BY NAME.
const pending = (files: string[], invokers: string[], pendingTools: object[]) =>
  JSON.stringify({ recorded: "2026-09-18", law: "selftest", files, invokers, pendingTools });
const entry = (tool: string, callers: string[] = []) => ({ tool, row: "T-9", reason: "selftest", recorded: "2026-09-18", callers });

arm("a .ts caller excused by a pending tool", "green", (r) => {
  writeFileSync(join(r, "tool.py"), "x\n");
  writeFileSync(join(r, "caller.ts"), 'spawnSync("python3", ["tool.py", "get"]);\n');
  writeFileSync(join(r, LIST), pending(["tool.py"], [], [entry("tool.py", ["caller.ts"])]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => existsSync(join(r, "tool.py")) && existsSync(join(r, "caller.ts")));

// The control. Without it the arm above proves only that the wall is green, not that the entry is
// what made it green — the two-lists-agreeing failure applied to the exclusion itself.
arm("the same caller with NO pending entry", "red", (r) => {
  writeFileSync(join(r, "tool.py"), "x\n");
  writeFileSync(join(r, "caller.ts"), 'spawnSync("python3", ["tool.py", "get"]);\n');
  writeFileSync(join(r, LIST), list(["tool.py"]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => !readFileSync(join(r, LIST), "utf8").includes("pendingTools"));

// EXPIRY, the property that makes this a disposition. When V4-143 lands and the tool is gone, every
// exclusion it granted has to die in the same instant — with no edit to the wall and no seat
// remembering to do it.
arm("a pending entry whose tool is GONE", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, LIST), pending(["a.py"], [], [entry("tool.py")]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => !existsSync(join(r, "tool.py")));

arm("a pending entry for a tool that was never debt", "red", (r) => {
  writeFileSync(join(r, "a.py"), "x\n");
  writeFileSync(join(r, "tool.py"), "x\n");
  writeFileSync(join(r, LIST), pending(["a.py"], [], [entry("tool.py")]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => existsSync(join(r, "tool.py")));

// THE NARROWNESS ARM. The strip removes the pending tool's own invocations and nothing else, so a
// caller that also shells python for its own reasons keeps its charge in full. Without this, one
// entry would launder every python mention in every file that happens to call the pending tool.
// THE NARROWNESS ARM, and it is where this mechanism is honest about its limit. The entry NAMES
// the files it excuses, so a second caller of the same pending tool is charged until somebody adds
// it deliberately and dates it. That is the property a regex cannot give: `spawnSync("python3",
// [manifest])` and `spawnSync("python3", ["-c", ...])` are indistinguishable without dataflow, so
// the boundary is an enumerated list rather than a pattern, and every excused file is PRINTED on
// every run so the list cannot grow unread.
arm("a second caller the entry does NOT name", "red", (r) => {
  writeFileSync(join(r, "tool.py"), "x\n");
  writeFileSync(join(r, "caller.ts"), 'spawnSync("python3", ["tool.py"]);\n');
  writeFileSync(join(r, "other.ts"), 'spawnSync("python3", ["tool.py"]);\n');
  writeFileSync(join(r, LIST), pending(["tool.py"], [], [entry("tool.py", ["caller.ts"])]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => existsSync(join(r, "other.ts")));

// A named caller that stopped needing the exclusion is stale in the same way a burn-down line is.
arm("a named caller that no longer mentions python", "red", (r) => {
  writeFileSync(join(r, "tool.py"), "x\n");
  writeFileSync(join(r, "caller.ts"), "export const a = 1;\n");
  writeFileSync(join(r, LIST), pending(["tool.py"], [], [entry("tool.py", ["caller.ts"])]));
  git(r, "add", "-A"); git(r, "commit", "-qm", "base");
}, (r) => !readFileSync(join(r, "caller.ts"), "utf8").includes("python"));

console.log(failures ? `\nFAIL: no-python selftest — ${failures} arm(s)` : "\nOK: no-python selftest — every arm graded a setup it verified");
process.exit(failures ? 1 : 0);
