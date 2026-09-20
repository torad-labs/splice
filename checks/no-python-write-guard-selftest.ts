#!/usr/bin/env bun
/** Red-green proof for checks/no-python-write-guard.ts, the PreToolUse half of the no-python wall.
 *
 *  These arms existed first as a hand-run transcript in a terminal, which is precisely the shape
 *  this repo has already been burned by: gate.sh's own comment records a routing guard "defeated by
 *  a single `#`" that survived into the branch because "its red-proofs were hand-run transcripts in
 *  a ledger note, so nothing re-ran them". A guard that refuses writes is the last thing that should
 *  be proven once and then trusted — if it silently stops refusing, the only signal is Python
 *  reappearing weeks later.
 *
 *  THE ALLOW ARMS MATTER MORE THAN THE BLOCK ARMS HERE. A write guard that over-refuses is worse
 *  than no guard: it would block the conversions that burn the list down, and the seat it blocks
 *  cannot route around it without violating the no-bypass law. So the existing-.py arm and the
 *  listed-invoker arm are the ones to watch. */
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { burndown } from "./no-python.ts";

const ROOT = resolve(import.meta.dir, "..");
let failures = 0;

function guard(payload: unknown): number {
  const r = spawnSync("bun", ["checks/no-python-write-guard.ts"], {
    cwd: ROOT,
    input: JSON.stringify(payload),
    encoding: "utf8",
  });
  return r.status ?? 1;
}

/** An arm whose FIXTURE CLASS no longer exists in the repo: it retires by name instead of failing.
 *
 *  This is only honest when the absence is READ FROM THE SOURCE, never assumed. The burn-down
 *  reaching zero is the terminal state this whole wall exists to produce (V4-143 deleted the last
 *  tracked .py on 2026-09-20), and an arm that grades "a file from the list" then has no list to
 *  draw from. A silent skip here would be the fail-open this file's own header rails against, so
 *  `vacant` must PROVE the class is empty; if the list is non-empty and the fixture is merely
 *  missing, it returns false and the arm fails exactly as before. */
function vacant(label: string, isEmpty: boolean, why: string): boolean {
  if (!isEmpty) return false;
  console.log(`  VACANT ${label} — ${why}`);
  return true;
}

function arm(label: string, expect: "block" | "allow", payload: unknown, setupOk: () => boolean, vacantWhen?: () => boolean) {
  if (!setupOk()) {
    if (vacantWhen?.()) return;
    console.log(`  FAIL  ${label} — SETUP did not take; arm graded nothing`);
    failures++;
    return;
  }
  const rc = guard(payload);
  const blocked = rc === 2;
  if ((expect === "block") === blocked) console.log(`  PASS  ${label} -> ${blocked ? "BLOCKED (2)" : `allowed (${rc})`}`);
  else {
    console.log(`  FAIL  ${label} -> ${blocked ? "BLOCKED (2)" : `allowed (${rc})`}, expected ${expect}`);
    failures++;
  }
}

const write = (p: string, content: string) => ({ tool_name: "Write", tool_input: { file_path: `${ROOT}/${p}`, content } });
const edit = (p: string, next: string) => ({ tool_name: "Edit", tool_input: { file_path: `${ROOT}/${p}`, new_string: next } });

// The two fixtures come from the LIVE burn-down rather than a hard-coded name: the list shrinks
// every few minutes while the conversion rows run, and an arm pinned to a file somebody just
// converted would grade a setup that no longer exists.
const list = burndown();
const listedPy = (list.files ?? [])[0] ?? "";
const listedInvoker = (list.invokers ?? []).find((f) => f.endsWith(".sh")) ?? "";

arm("a NEW .py file", "block", write("checks/brand-new.py", "x = 1\n"), () => true);
arm("an EXISTING listed .py stays writable", "allow", write(listedPy, "x = 1\n"), () => listedPy.endsWith(".py"),
  () => vacant("an EXISTING listed .py stays writable", (list.files ?? []).length === 0,
    "the burn-down lists no tracked .py — there is no longer a file this arm could allow"));
arm("a .ts that SHELLS into python3", "block", write("checks/newthing.ts", `spawnSync("python3", []);\n`), () => true);
arm("a .ts whose PROSE names python3", "block", write("checks/newthing.ts", "// run python3 foo.py\n"), () => true);
arm("a clean .ts", "allow", write("checks/newthing.ts", "export const a = 1;\n"), () => true);
arm("a LISTED invoker may still be edited", "allow", edit(listedInvoker, "python3 x.py\n"), () => listedInvoker.endsWith(".sh"));
arm("an unrelated edit to a listed invoker", "allow", edit(listedInvoker, "echo hi\n"), () => listedInvoker.endsWith(".sh"));
arm("the wall's own source is exempt", "allow", write("checks/no-python.ts", "python3\n"), () => true);
arm("a path OUTSIDE the repo", "allow", { tool_name: "Write", tool_input: { file_path: "/tmp/scratch.py", content: "x = 1\n" } }, () => true);
arm("a non-write tool", "allow", { tool_name: "Bash", tool_input: { command: "python3 x.py" } }, () => true);
arm("MultiEdit smuggling it in a later edit", "block", {
  tool_name: "MultiEdit",
  tool_input: { file_path: `${ROOT}/checks/newthing.ts`, edits: [{ new_string: "const a = 1;" }, { new_string: "spawnSync('python3', []);" }] },
}, () => true);
arm("empty stdin is not a verdict", "allow", "", () => true);

// THE WRITE-TIME HALF OF THE SEVENTH CENSUS, on the file it actually happened to. inside.sh is a
// LISTED INVOKER, so the naming-python arm above deliberately lets it through — and that
// exemption is exactly what let the slip land. These arms pin that being allowed to name python
// never means being allowed to name it in front of a .ts.
arm("the wrong runtime into a LISTED invoker", "block", edit(listedInvoker, 'python3 "$HERE/mock_chat.ts" &'), () => listedInvoker.endsWith(".sh"));
arm("the same line with the interpreter fixed", "allow", edit(listedInvoker, 'bun "$HERE/mock_chat.ts" &'), () => listedInvoker.endsWith(".sh"));
arm("bun running a .py in a caller", "block", edit(listedInvoker, "bun checks/config/x.py\n"), () => listedInvoker.endsWith(".sh"));
arm("a comment quoting the wrong runtime", "allow", edit(listedInvoker, '# was python3 "$HERE/mock_chat.ts"\n'), () => listedInvoker.endsWith(".sh"));

// THE PENDING-TOOL DISPOSITION, both halves, in the guard. The negative half is already above
// ("a .ts that SHELLS into python3" must still block) and it EARNED its place: the first cut of
// this wiring passed the whole pendingTools array where the function now takes one tool string,
// so `line.includes(tool)` never matched and every interpreter-only line was stripped for EVERY
// file. The guard stopped refusing `spawnSync("python3", [])` anywhere in the repo. It passed in
// the worktree — where it had been proven before the signature changed — and failed only when run
// against the staged tree, which is the one being committed.
const namedCaller = (burndown().pendingTools ?? [])[0]?.callers?.[0] ?? "";
arm("a NAMED caller of a pending tool", "allow", edit(namedCaller, 'spawnSync("python3", [manifest, "laws"]);\n'), () => namedCaller.endsWith(".ts"),
  () => vacant("a NAMED caller of a pending tool", (burndown().pendingTools ?? []).length === 0,
    "no pending-tool exclusions remain — there is no named caller this arm could allow"));
// NOT coupled to namedCaller: this arm writes into checks/newthing.ts, so the refusal it pins
// holds whether or not any pending-tool entry exists. Gating it on a fixture it never uses made
// it fail for an unrelated reason the moment the last pending tool retired.
arm("a file the entry does NOT name", "block", edit("checks/newthing.ts", 'spawnSync("python3", [manifest, "laws"]);\n'), () => true);

console.log(failures ? `\nFAIL: no-python write-guard selftest — ${failures} arm(s)` : "\nOK: no-python write-guard selftest — every arm graded a setup it verified, or retired by name against an empty list it read from the source");
process.exit(failures ? 1 : 0);
