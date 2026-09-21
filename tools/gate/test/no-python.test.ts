// Red-green proof for the no-Python wall (src/lib/no-python.ts), both lifecycles.
//
// THE WALL ARMS grade fixture repositories in-process. Every arm asserts its SETUP before it
// grades: the first version of this suite reported "stale entry -> GREEN, expected red" when
// `git rm` had actually refused and the mutation never happened. An arm that grades a mutation it
// did not make is the failure this whole wall family exists to catch.
//
// THE GUARD ARMS drive `bun tools/gate no-python --guard` as a subprocess against THIS repository
// with synthetic PreToolUse events, exactly as .claude/settings.json runs it. These existed first
// as a hand-run transcript, which is precisely the shape this repo has been burned by: a guard
// that refuses writes is the last thing that should be proven once and then trusted — if it
// silently stops refusing, the only signal is Python reappearing weeks later. THE ALLOW ARMS
// MATTER MORE THAN THE BLOCK ARMS: a write guard that over-refuses would block the conversions
// that burn the list down, and the seat it blocks cannot route around it.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ALLOW, WallError, burndown, wall } from "../src/lib/no-python.ts";
import { layout } from "../src/lib/repo.ts";

// ─── the wall ──────────────────────────────────────────────────────────────────────────────────

function git(cwd: string, ...args: string[]) {
  const r = spawnSync("git", ["-C", cwd, ...args], { encoding: "utf8" });
  return { rc: r.status ?? 1, out: (r.stdout || "").trim(), err: (r.stderr || "").trim() };
}
const tracked = (cwd: string) => git(cwd, "ls-files", "*.py").out.split("\n").map((s) => s.trim()).filter(Boolean);
const untrackedPy = (cwd: string) =>
  git(cwd, "ls-files", "--others", "--exclude-standard", "*.py").out.split("\n").map((s) => s.trim()).filter(Boolean);

/** The wall's verdict over a fixture: RED is any problem or a census that could not run. */
function verdict(root: string): { red: boolean; detail: string } {
  try {
    const report = wall(root);
    return { red: report.problems.length > 0, detail: report.problems.join("\n") };
  } catch (e) {
    if (e instanceof WallError) return { red: true, detail: `${e.message} (exit ${e.exit})` };
    throw e;
  }
}

function arm(label: string, expect_: "red" | "green", build: (root: string) => void, setupOk: (root: string) => boolean) {
  test(`${label} -> ${expect_}`, () => {
    const root = mkdtempSync(join(tmpdir(), "nopy-"));
    try {
      mkdirSync(join(root, "tools", "gate", "config"), { recursive: true });
      mkdirSync(join(root, "checks"), { recursive: true });
      git(root, "init", "-q");
      git(root, "config", "user.email", "t@t");
      git(root, "config", "user.name", "t");
      build(root);
      expect(setupOk(root), `${label} — SETUP did not take; the arm would grade nothing`).toBe(true);
      const { red, detail } = verdict(root);
      expect(red, `${label}: expected ${expect_}, got ${red ? "RED" : "GREEN"}\n${detail}`).toBe(expect_ === "red");
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
}
// Pretty-printed like the real list: git's rename detection scores LINES in common, so a one-line
// JSON whose only line changed is a delete plus an add, and the moved-list arms could never see a
// rename. The real python-burndown.json is one entry per line.
const list = (files: string[], invokers: string[] = []) =>
  JSON.stringify({ recorded: "2026-09-18", law: "selftest", files, invokers }, null, 2);
const w = (root: string, rel: string, text: string) => {
  mkdirSync(join(root, rel, ".."), { recursive: true });
  writeFileSync(join(root, rel), text);
};
const commit = (root: string, msg = "base") => {
  git(root, "add", "-A");
  git(root, "commit", "-qm", msg);
};

describe("the no-python wall", () => {
  arm("recorded set matches", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => tracked(r).join() === "a.py");

  arm("a NEW .py appears", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r);
    w(r, "sneaky.py", "print('hi')\n");
    git(r, "add", "-A");
  }, (r) => tracked(r).includes("sneaky.py"));

  arm("an EMPTY new .py", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r);
    w(r, "empty.py", "");
    git(r, "add", "-A");
  }, (r) => tracked(r).includes("empty.py"));

  arm("a listed file was converted", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "b.py", "x\n");
    w(r, ALLOW, list(["a.py", "b.py"]));
    commit(r);
    git(r, "rm", "-qf", "b.py");
  }, (r) => !tracked(r).includes("b.py"));

  // ── the second census: Python the first one cannot see ─────────────────────────────────────
  arm("a .sh that shells into python3, unlisted", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "run.sh", "#!/bin/sh\npython3 -c 'print(1)'\n");
    w(r, ALLOW, list(["a.py"], []));
    commit(r);
  }, (r) => tracked(r).includes("a.py"));

  // The half nobody notices: prose goes stale where an invocation fails loudly.
  arm("a README that TEACHES python3, unlisted", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "README.md", "Run the check with `python3 checks/foo.py`.\n");
    w(r, ALLOW, list(["a.py"], []));
    commit(r);
  }, (r) => tracked(r).includes("a.py"));

  arm("a listed invoker that was already converted", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "run.sh", "#!/bin/sh\nbun x.ts\n"); // no python any more
    w(r, ALLOW, list(["a.py"], ["run.sh"]));
    commit(r);
  }, (r) => tracked(r).includes("a.py"));

  arm("both censuses matching their lists", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "run.sh", "#!/bin/sh\npython3 -c 'print(1)'\n");
    w(r, ALLOW, list(["a.py"], ["run.sh"]));
    commit(r);
  }, (r) => tracked(r).includes("a.py"));

  // A .py is counted ONCE, by the file census — never double-counted as its own invoker.
  arm("a .py whose docstring names python3", "green", (r) => {
    w(r, "a.py", '"""run with python3 a.py"""\n');
    w(r, ALLOW, list(["a.py"], []));
    commit(r);
  }, (r) => tracked(r).includes("a.py"));

  // ── the compat modules: a NAME is not an invocation ────────────────────────────────────────
  // tools/e2e/src/compat/python-{http,json,values}.ts exist so this repo does NOT shell into
  // Python; `\bpython\b` matches inside a hyphenated name, so a file importing one would be charged
  // for complying — the `no-python` narrowing a second time (src/lib/no-python.ts, namesPython).
  // The red arm is the boundary: the strip removes the NAME, never the token beside it.
  arm("a .ts that only IMPORTS a compat module, unlisted", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "checks/x.ts", 'import { loads } from "../tools/e2e/src/compat/python-json.ts";\n');
    w(r, ALLOW, list(["a.py"], []));
    commit(r);
  }, (r) => git(r, "ls-files", "checks/x.ts").out === "checks/x.ts");

  arm("a .ts that imports a compat module AND shells into python3, unlisted", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "checks/x.ts", 'import { loads } from "../tools/e2e/src/compat/python-json.ts";\nspawnSync("python3", ["-c", "1"]);\n');
    w(r, ALLOW, list(["a.py"], []));
    commit(r);
  }, (r) => git(r, "ls-files", "checks/x.ts").out === "checks/x.ts");

  // ── the third census: Python that is not in git at all ─────────────────────────────────────
  arm("an UNTRACKED scratch .py in the worktree", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r);
    w(r, "scratch.py", "import pathlib\n"); // written, never added
  }, (r) => !tracked(r).includes("scratch.py") && untrackedPy(r).includes("scratch.py"));

  // The leg must charge the AUTHOR, never the package manager. The setup asserts only that the
  // file is on disk and untracked — asserting it is absent from the census would be asserting the
  // very thing this arm grades.
  arm("a .gitignore'd vendor .py is NOT charged", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, list(["a.py"]));
    w(r, ".gitignore", "vendor/\n");
    w(r, "vendor/dep.py", "x\n");
    commit(r);
  }, (r) => existsSync(join(r, "vendor", "dep.py")) && !tracked(r).includes("vendor/dep.py"));

  // ── the fourth census: a caller that outlived the file it calls ────────────────────────────
  arm("a .sh calling a script that does NOT exist", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "run.sh", "bun checks/config/gone.ts check .\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => existsSync(join(r, "run.sh")) && !existsSync(join(r, "checks", "config", "gone.ts")));

  arm("a .sh calling a script that DOES exist", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "checks/exists.ts", "//\n");
    w(r, "run.sh", "bun checks/exists.ts\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => existsSync(join(r, "checks", "exists.ts")));

  // ── the fifth census: a ledger instruction naming a file that is gone ──────────────────────
  // Graded ONLY on rows that have not run yet. The two green arms are the boundary, and they
  // matter more than the red one: without them the obvious "any missing path is bad"
  // implementation passes its red arm and then charges correct ledger authorship.
  const ledger = (r: string, id: string, status: string, verify: string) =>
    w(r, ".dev/campaigns/c.toml", `[[items]]\nid = "${id}"\nstatus = "${status}"\nverify = "${verify}"\n`);

  arm("a TODO row whose verify names a missing .py", "red", (r) => {
    w(r, "a.py", "x\n");
    ledger(r, "T-1", "todo", "python3 checks/gone.py");
    w(r, ALLOW, list(["a.py"], [".dev/campaigns/c.toml"]));
    commit(r);
  }, (r) => !existsSync(join(r, "checks", "gone.py")));

  arm("a TODO row naming a .ts it will CREATE", "green", (r) => {
    w(r, "a.py", "x\n");
    ledger(r, "T-2", "todo", "bun checks/not-yet-written.ts");
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => !existsSync(join(r, "checks", "not-yet-written.ts")));

  arm("a VERIFIED row's verify is history, not an instruction", "green", (r) => {
    w(r, "a.py", "x\n");
    ledger(r, "T-3", "verified", "python3 checks/long-since-converted.py");
    w(r, ALLOW, list(["a.py"], [".dev/campaigns/c.toml"]));
    commit(r);
  }, (r) => !existsSync(join(r, "checks", "long-since-converted.py")));

  // A .py deleted in the worktree but NOT yet staged is still `git ls-files` tracked.
  arm("a tracked .py DELETED but not yet staged", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r);
    rmSync(join(r, "a.py"));
  }, (r) => !existsSync(join(r, "a.py")) && tracked(r).includes("a.py"));

  arm("a TODO row naming the WRONG runtime for the extension", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "checks/w.ts", "//\n");
    ledger(r, "T-4", "todo", "python3 checks/w.ts");
    w(r, ALLOW, list(["a.py"], [".dev/campaigns/c.toml"]));
    commit(r);
  }, (r) => existsSync(join(r, "checks", "w.ts")));

  arm("a TODO row whose files= names a missing .py", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ".dev/campaigns/c.toml", `[[items]]\nid = "T-5"\nstatus = "todo"\nfiles = ["checks/vanished.py"]\n`);
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => !existsSync(join(r, "checks", "vanished.py")));

  arm("a TODO row whose files= is a GLOB", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, ".dev/campaigns/c.toml", `[[items]]\nid = "T-6"\nstatus = "todo"\nfiles = ["checks/*.py"]\n`);
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => readFileSync(join(r, ".dev", "campaigns", "c.toml"), "utf8").includes('files = ["checks/*.py"]'));

  // The fifth surface: a registry row whose wall= names a file that is gone.
  arm("a registry wall= naming a missing file", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "walls/reg.toml", `[[law]]\ntag = "L1"\nwall = "walls/gone.py"\n`);
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => !existsSync(join(r, "walls", "gone.py")));

  arm("a registry wall= that is EMPTY is not charged", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "walls/reg.toml", `[[law]]\ntag = "L2"\nwall = ""\n`);
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => readFileSync(join(r, "walls", "reg.toml"), "utf8").includes('wall = ""'));

  arm("an unparseable burn-down list", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, "not json");
    commit(r);
  }, (r) => tracked(r).includes("a.py"));

  // ── the sixth surface: the list itself, graded against its FIRST commit ────────────────────
  arm("the burn-down GREW after its first commit", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r, "birth");
    w(r, "b.py", "y\n");
    w(r, ALLOW, list(["a.py", "b.py"]));
    commit(r, "sneak it in");
  }, (r) => !git(r, "show", `HEAD~1:${ALLOW}`).out.includes("b.py") && git(r, "show", `HEAD:${ALLOW}`).out.includes("b.py"));

  arm("the burn-down SHRANK", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "b.py", "y\n");
    w(r, ALLOW, list(["a.py", "b.py"]));
    commit(r, "birth");
    rmSync(join(r, "b.py"));
    w(r, ALLOW, list(["a.py"]));
    commit(r, "converted b");
  }, (r) => git(r, "show", `HEAD~1:${ALLOW}`).out.includes("b.py") && !git(r, "show", `HEAD:${ALLOW}`).out.includes("b.py"));

  // A RENAME IS NOT GROWTH — for invokers (#156 moved docs/ to .docs/). `files` keeps the refusal.
  arm("an invoker RENAMED by git since birth", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "docs/plan.md", "# plan\n\nthe old python3 doctor step is documented here\n");
    w(r, ALLOW, list(["a.py"], ["docs/plan.md"]));
    commit(r, "birth");
    git(r, "mv", "docs/plan.md", "plan.md");
    w(r, ALLOW, list(["a.py"], ["plan.md"]));
    commit(r, "consolidation moves the doc");
  }, (r) => git(r, "diff", "--name-status", "-M", "HEAD~1", "HEAD").out.includes("R100\tdocs/plan.md\tplan.md"));

  arm("a .py RENAMED by git since birth", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, list(["a.py"]));
    commit(r, "birth");
    git(r, "mv", "a.py", "b.py");
    w(r, ALLOW, list(["b.py"]));
    commit(r, "python reorganised, not converted");
  }, (r) => git(r, "diff", "--name-status", "-M", "HEAD~1", "HEAD").out.includes("R100\ta.py\tb.py"));

  // Each key is graded from the first commit in which THAT key has content.
  arm("a burn-down key that did not exist at the first commit", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "s.sh", "#!/bin/sh\npython3 a.py\n");
    w(r, ALLOW, JSON.stringify({ recorded: "2026-09-18", law: "selftest", files: ["a.py"] }, null, 2));
    commit(r, "birth, files only");
    w(r, ALLOW, list(["a.py"], ["s.sh"]));
    commit(r, "the second census arrives");
  }, (r) => !git(r, "show", `HEAD~1:${ALLOW}`).out.includes("invokers") && git(r, "show", `HEAD:${ALLOW}`).out.includes("s.sh"));

  // THE LIST ITSELF MOVED (restructure PR 5: checks/config/ -> tools/gate/config/). The birth is
  // the list as first committed AT ITS OLD PATH; a ratchet that started history at the move would
  // compare the list against itself and pass a line smuggled in beside the move.
  arm("the list moved, and a line added with the move is still growth", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "old/config/list.json", list(["a.py"]));
    commit(r, "birth at the old path");
    mkdirSync(join(r, "tools", "gate", "config"), { recursive: true });
    git(r, "mv", "old/config/list.json", ALLOW);
    w(r, "b.py", "y\n");
    w(r, ALLOW, list(["a.py", "b.py"]));
    commit(r, "moved, and grew");
  }, (r) => git(r, "diff", "--name-status", "-M", "HEAD~1", "HEAD").out.includes(`\told/config/list.json\t${ALLOW}`));

  arm("the list moved without growing", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "old/config/list.json", list(["a.py"]));
    commit(r, "birth at the old path");
    git(r, "mv", "old/config/list.json", ALLOW);
    commit(r, "moved");
  }, (r) => git(r, "diff", "--name-status", "-M", "HEAD~1", "HEAD").out.includes(`R100\told/config/list.json\t${ALLOW}`));

  // ── the seventh census: a live caller running the WRONG runtime for the extension ──────────
  // The red arm carries splice-builder2's ACTUAL slip, quotes and all: a paraphrased arm
  // (`python3 wall.ts`) passes against a regex blind to the only form it has ever had to catch.
  arm("a .sh running a .ts through python3, path QUOTED", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "checks/mock_chat.ts", "//\n");
    w(r, "inside.sh", 'HERE=checks\npython3 "$HERE/mock_chat.ts" --port 8080 &\n');
    w(r, ALLOW, list(["a.py"], ["inside.sh"]));
    commit(r);
  }, (r) => readFileSync(join(r, "inside.sh"), "utf8").includes('python3 "$HERE/mock_chat.ts"'));

  arm("the same caller with the interpreter fixed", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "checks/mock_chat.ts", "//\n");
    w(r, "inside.sh", 'HERE=checks\nbun "$HERE/mock_chat.ts" --port 8080 &\n');
    w(r, ALLOW, list(["a.py"]));
    commit(r);
  }, (r) => readFileSync(join(r, "inside.sh"), "utf8").includes('bun "$HERE/mock_chat.ts"'));

  arm("a .sh running a .py through bun", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "run.sh", "bun a.py\n");
    w(r, ALLOW, list(["a.py"], ["run.sh"]));
    commit(r);
  }, (r) => existsSync(join(r, "a.py")));

  // THE ARM THAT LICENSES THE WHOLE CENSUS: comment lines are prose, or this would charge every
  // seat that documented the defect it fixed.
  arm("a COMMENT quoting the wrong-runtime form", "green", (r) => {
    w(r, "a.py", "x\n");
    w(r, "run.sh", '# was: python3 "$HERE/mock_chat.ts", fixed in this commit\nbun a.ts\n');
    w(r, "a.ts", "//\n");
    w(r, ALLOW, list(["a.py"], ["run.sh"]));
    commit(r);
  }, (r) => readFileSync(join(r, "run.sh"), "utf8").startsWith("#"));

  // ── the third disposition: a caller excused ONLY because the tool it calls is still Python ─
  // THE RED ARMS MATTER MORE THAN THE GREEN ONE: an exclusion mechanism is worth exactly what its
  // expiry is worth, and "temporary" allowlists in this repo's history have all been permanent.
  const pending = (files: string[], invokers: string[], pendingTools: object[]) =>
    JSON.stringify({ recorded: "2026-09-18", law: "selftest", files, invokers, pendingTools }, null, 2);
  const entry = (tool: string, callers: string[] = []) => ({ tool, row: "T-9", reason: "selftest", recorded: "2026-09-18", callers });

  arm("a .ts caller excused by a pending tool", "green", (r) => {
    w(r, "tool.py", "x\n");
    w(r, "caller.ts", 'spawnSync("python3", ["tool.py", "get"]);\n');
    w(r, ALLOW, pending(["tool.py"], [], [entry("tool.py", ["caller.ts"])]));
    commit(r);
  }, (r) => existsSync(join(r, "tool.py")) && existsSync(join(r, "caller.ts")));

  // The control: without it the arm above proves only that the wall is green, not that the
  // entry is what made it green.
  arm("the same caller with NO pending entry", "red", (r) => {
    w(r, "tool.py", "x\n");
    w(r, "caller.ts", 'spawnSync("python3", ["tool.py", "get"]);\n');
    w(r, ALLOW, list(["tool.py"]));
    commit(r);
  }, (r) => !readFileSync(join(r, ALLOW), "utf8").includes("pendingTools"));

  arm("a pending entry whose tool is GONE", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, ALLOW, pending(["a.py"], [], [entry("tool.py")]));
    commit(r);
  }, (r) => !existsSync(join(r, "tool.py")));

  arm("a pending entry for a tool that was never debt", "red", (r) => {
    w(r, "a.py", "x\n");
    w(r, "tool.py", "x\n");
    w(r, ALLOW, pending(["a.py"], [], [entry("tool.py")]));
    commit(r);
  }, (r) => existsSync(join(r, "tool.py")));

  // THE NARROWNESS ARM: the entry NAMES the files it excuses; a second caller is charged until
  // somebody adds it deliberately and dates it.
  arm("a second caller the entry does NOT name", "red", (r) => {
    w(r, "tool.py", "x\n");
    w(r, "caller.ts", 'spawnSync("python3", ["tool.py"]);\n');
    w(r, "other.ts", 'spawnSync("python3", ["tool.py"]);\n');
    w(r, ALLOW, pending(["tool.py"], [], [entry("tool.py", ["caller.ts"])]));
    commit(r);
  }, (r) => existsSync(join(r, "other.ts")));

  arm("a named caller that no longer mentions python", "red", (r) => {
    w(r, "tool.py", "x\n");
    w(r, "caller.ts", "export const a = 1;\n");
    w(r, ALLOW, pending(["tool.py"], [], [entry("tool.py", ["caller.ts"])]));
    commit(r);
  }, (r) => !readFileSync(join(r, "caller.ts"), "utf8").includes("python"));
});

// ─── the guard ─────────────────────────────────────────────────────────────────────────────────

const { repoRoot } = layout();

function guard(payload: unknown): number {
  const r = spawnSync(process.execPath, [join(repoRoot, "tools", "gate", "index.ts"), "no-python", "--guard"], {
    cwd: repoRoot,
    input: typeof payload === "string" ? payload : JSON.stringify(payload),
    encoding: "utf8",
  });
  return r.status ?? 1;
}
const write = (p: string, content: string) => ({ tool_name: "Write", tool_input: { file_path: `${repoRoot}/${p}`, content } });
const edit = (p: string, next: string) => ({ tool_name: "Edit", tool_input: { file_path: `${repoRoot}/${p}`, new_string: next } });

/** The two fixtures come from the LIVE burn-down rather than a hard-coded name: the list shrinks
 *  while conversion rows run. A fixture class that no longer exists in the repo RETIRES by name
 *  instead of failing — but only when the absence is READ FROM THE SOURCE: the burn-down reaching
 *  zero is the terminal state this wall exists to produce (V4-143 deleted the last tracked .py on
 *  2026-09-20), and an arm that grades "a file from the list" then has no list to draw from. A
 *  silent skip would be the fail-open this file's header rails against, so the emptiness is
 *  asserted against the list, never assumed. */
const live = burndown(repoRoot);
const listedPy = (live.files ?? [])[0] ?? "";
const listedInvoker = (live.invokers ?? []).find((f) => f.endsWith(".sh")) ?? "";
const namedCaller = (live.pendingTools ?? [])[0]?.callers?.[0] ?? "";

function guardArm(label: string, expect_: "block" | "allow", payload: unknown, setupOk: () => boolean, vacant?: () => boolean) {
  test(`${label} -> ${expect_}`, () => {
    if (!setupOk()) {
      if (vacant?.()) return; // the fixture class is empty, proven from the source above
      throw new Error(`${label} — SETUP did not take; the arm would grade nothing`);
    }
    const rc = guard(payload);
    expect(rc === 2 ? "block" : "allow", `${label}: exit ${rc}`).toBe(expect_);
  });
}

describe("the no-python write guard", () => {
  guardArm("a NEW .py file", "block", write("checks/brand-new.py", "x = 1\n"), () => true);
  guardArm("an EXISTING listed .py stays writable", "allow", write(listedPy, "x = 1\n"),
    () => listedPy.endsWith(".py"), () => (live.files ?? []).length === 0);
  guardArm("a .ts that SHELLS into python3", "block", write("checks/newthing.ts", `spawnSync("python3", []);\n`), () => true);
  guardArm("a .ts whose PROSE names python3", "block", write("checks/newthing.ts", "// run python3 foo.py\n"), () => true);
  guardArm("a clean .ts", "allow", write("checks/newthing.ts", "export const a = 1;\n"), () => true);
  guardArm("a LISTED invoker may still be edited", "allow", edit(listedInvoker, "python3 x.py\n"), () => listedInvoker.endsWith(".sh"));
  guardArm("an unrelated edit to a listed invoker", "allow", edit(listedInvoker, "echo hi\n"), () => listedInvoker.endsWith(".sh"));
  guardArm("the wall's own source is exempt", "allow", write("tools/gate/src/lib/no-python.ts", "python3\n"), () => true);
  guardArm("a path OUTSIDE the repo", "allow", { tool_name: "Write", tool_input: { file_path: "/tmp/scratch.py", content: "x = 1\n" } }, () => true);
  guardArm("a non-write tool", "allow", { tool_name: "Bash", tool_input: { command: "python3 x.py" } }, () => true);
  guardArm("MultiEdit smuggling it in a later edit", "block", {
    tool_name: "MultiEdit",
    tool_input: { file_path: `${repoRoot}/checks/newthing.ts`, edits: [{ new_string: "const a = 1;" }, { new_string: "spawnSync('python3', []);" }] },
  }, () => true);
  guardArm("empty stdin is not a verdict", "allow", "", () => true);
  guardArm("unparseable stdin fails OPEN, never closed", "allow", "{not json", () => true);

  // THE WRITE-TIME HALF OF THE SEVENTH CENSUS, on the file it actually happened to: a LISTED
  // invoker is allowed to name python — never to name it in front of a .ts.
  guardArm("the wrong runtime into a LISTED invoker", "block", edit(listedInvoker, 'python3 "$HERE/mock_chat.ts" &'), () => listedInvoker.endsWith(".sh"));
  guardArm("the same line with the interpreter fixed", "allow", edit(listedInvoker, 'bun "$HERE/mock_chat.ts" &'), () => listedInvoker.endsWith(".sh"));
  guardArm("bun running a .py in a caller", "block", edit(listedInvoker, "bun checks/config/x.py\n"), () => listedInvoker.endsWith(".sh"));
  guardArm("a comment quoting the wrong runtime", "allow", edit(listedInvoker, '# was python3 "$HERE/mock_chat.ts"\n'), () => listedInvoker.endsWith(".sh"));

  // THE PENDING-TOOL DISPOSITION in the guard. The negative half ("a .ts that SHELLS into python3"
  // must still block) EARNED its place: a first cut passed the whole pendingTools array where the
  // strip takes one tool string, so every interpreter-only line was stripped for EVERY file.
  guardArm("a NAMED caller of a pending tool", "allow", edit(namedCaller, 'spawnSync("python3", [manifest, "laws"]);\n'),
    () => namedCaller.endsWith(".ts"), () => (live.pendingTools ?? []).length === 0);
  guardArm("a file the entry does NOT name", "block", edit("checks/newthing.ts", 'spawnSync("python3", [manifest, "laws"]);\n'), () => true);
});
