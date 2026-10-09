// Red-green proof for the no-Python wall (src/lib/no-python.ts), both lifecycles.
//
// THE WALL ARMS grade fixture repositories in-process, one fixture ROOT per shape. Every arm asserts
// its SETUP before it grades: an arm that grades a mutation it did not make is the failure this whole
// wall family exists to catch. There is no burn-down list any more, so every charge is a new one and
// every green arm is a file that names Python without running or installing it.
//
// THE GUARD ARMS drive `bun tools/gate no-python --guard` as a subprocess against THIS repository
// with synthetic PreToolUse events, exactly as .claude/settings.json runs it. THE ALLOW ARMS MATTER
// MORE THAN THE BLOCK ARMS: a write guard that over-refuses blocks the work it exists to allow, and
// the seat it blocks cannot route around it.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { WallError, invokesPython, mismatchedRuntimes, wall } from "../src/lib/no-python.ts";
import { layout } from "../src/lib/repo.ts";

// ─── the wall ──────────────────────────────────────────────

function git(cwd: string, ...args: string[]) {
  const r = spawnSync("git", ["-C", cwd, ...args], { encoding: "utf8" });
  return { rc: r.status ?? 1, out: (r.stdout || "").trim(), err: (r.stderr || "").trim() };
}
const tracked = (cwd: string) => git(cwd, "ls-files", "*.py").out.split("\n").map((s) => s.trim()).filter(Boolean);
const untrackedPy = (cwd: string) =>
  git(cwd, "ls-files", "--others", "--exclude-standard", "*.py").out.split("\n").map((s) => s.trim()).filter(Boolean);
const isTracked = (cwd: string, path: string) => git(cwd, "ls-files", path).out === path;

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
const w = (root: string, rel: string, text: string) => {
  mkdirSync(join(root, rel, ".."), { recursive: true });
  writeFileSync(join(root, rel), text);
};
const commit = (root: string, msg = "base") => {
  git(root, "add", "-A");
  git(root, "commit", "-qm", msg);
};

describe("the no-python wall: the file census", () => {
  arm("a tracked .py", "red", (r) => {
    w(r, "sneaky.py", "print('hi')\n");
    commit(r);
  }, (r) => tracked(r).includes("sneaky.py"));

  arm("an EMPTY tracked .py", "red", (r) => {
    w(r, "empty.py", "");
    commit(r);
  }, (r) => tracked(r).includes("empty.py"));

  arm("a tree with no Python at all", "green", (r) => {
    w(r, "a.ts", "export const a = 1;\n");
    commit(r);
  }, (r) => isTracked(r, "a.ts") && tracked(r).length === 0);

  // A .py whose docstring names python3 is still a .py: counted by the file census, once.
  arm("a .py whose docstring names python3", "red", (r) => {
    w(r, "a.py", '"""run with python3 a.py"""\n');
    commit(r);
  }, (r) => tracked(r).includes("a.py"));

  // `git ls-files` lists a tracked file deleted in the worktree but not yet staged; it exists nowhere.
  arm("a tracked .py DELETED but not yet staged", "green", (r) => {
    w(r, "a.py", "x\n");
    commit(r);
    rmSync(join(r, "a.py"));
  }, (r) => !existsSync(join(r, "a.py")) && tracked(r).includes("a.py"));

  arm("an UNTRACKED scratch .py in the worktree", "red", (r) => {
    w(r, "a.ts", "export const a = 1;\n");
    commit(r);
    w(r, "scratch.py", "import pathlib\n"); // written, never added
  }, (r) => !tracked(r).includes("scratch.py") && untrackedPy(r).includes("scratch.py"));

  // The leg must charge the AUTHOR, never the package manager.
  arm("a .gitignore'd vendor .py is NOT charged", "green", (r) => {
    w(r, ".gitignore", "vendor/\n");
    w(r, "vendor/dep.py", "x\n");
    commit(r);
  }, (r) => existsSync(join(r, "vendor", "dep.py")) && !tracked(r).includes("vendor/dep.py"));
});

describe("the no-python wall: invocations by structure", () => {
  // Every RED arm is a way the repo would actually run or install Python; every GREEN arm names it
  // without running anything.
  const LEDGER = (status: string, verify: string) =>
    `[[items]]\nid = "R-1"\nstatus = "${status}"\nverify = "${verify}"\nnotes = ["ran python3 x.py once"]\n`;
  const SHAPES: readonly (readonly [string, string, string])[] = [
    ["a shebang", "tool", "#!/usr/bin/env python3\nprint(1)\n"],
    ["a shell command", "run.sh", "#!/bin/sh\npython3 -c 'print(1)'\n"],
    ["a shell command after &&", "run.sh", "#!/bin/sh\ncd x && python3 -m http.server\n"],
    ["a shell command with env words first", "run.sh", "#!/bin/sh\nFOO=1 python -c 1\n"],
    ["a command substitution", "run.sh", '#!/bin/sh\nV="$(python3 -V)"\n'],
    ["pip", "run.sh", "#!/bin/sh\npip install requests\n"],
    ["a Dockerfile RUN", "docker/Dockerfile", "FROM debian\nRUN python3 -m venv /v\n"],
    ["a Dockerfile python image", "docker/Dockerfile", "FROM python:3.12-slim\n"],
    ["a Dockerfile package install", "docker/Dockerfile", "FROM debian\nRUN apt-get install -y python3 curl\n"],
    ["a workflow run step", ".github/workflows/ci.yml", "jobs:\n  a:\n    steps:\n      - run: python3 x.sh\n"],
    ["a workflow setup-python step", ".github/workflows/ci.yml", "jobs:\n  a:\n    steps:\n      - uses: actions/setup-python@v5\n"],
    ["a package.json script", "package.json", '{"scripts":{"t":"python3 -m pytest"}}\n'],
    ["a Kotlin process spawn", "T.kt", 'val p = ProcessBuilder("python3", "-c", "pass").start()\n'],
    ["a TS process spawn", "t.ts", 'spawnSync("python", ["-c", "1"]);\n'],
    ["a Bun shell template", "t.ts", "await Bun.$`python3 -c 1`;\n"],
    ["a launcher config whose command is the interpreter", "T.kt", 'val c = """{"fake":{"command":"python3","args":["s.py"]}}"""\n'],
    ["a live ledger verify", ".dev/campaigns/c.toml", LEDGER("todo", "python3 checks/x.py --selftest")],
    ["an in-flight ledger verify", ".dev/campaigns/c.toml", LEDGER("in_flight", "bun a.ts && python3 b.py")],
  ];
  for (const [what, path, text] of SHAPES) {
    arm(what, "red", (r) => {
      w(r, path, text);
      commit(r);
    }, (r) => isTracked(r, path));
  }

  const MENTIONS: readonly (readonly [string, string, string])[] = [
    ["a README that teaches a python3 command", "README.md", "Run the check with `python3 checks/foo.py`.\n"],
    ["a CHANGELOG line", "CHANGELOG.md", "- ported the python3 harness to bun\n"],
    ["an svg screenshot", "doctor.svg", "<text>python3 --version</text>\n"],
    ["prose json", "wire.json", '{"note":"captured with python3 -m json.tool"}\n'],
    ["a toml comment", "tools/x.toml", '# was python3 once\nname = "x"\n'],
    ["a ledger note", ".dev/campaigns/c.toml", LEDGER("todo", "bun a.ts")],
    ["a done ledger verify (history, not an instruction)", ".dev/campaigns/c.toml", LEDGER("done", "python3 b.py")],
    ["a shell comment", "run.sh", "#!/bin/sh\n# python3 used to run this\necho ok\n"],
    ["a shell echo", "run.sh", '#!/bin/sh\necho "no python3 here"\n'],
    ["a TS comment", "t.ts", "// run it with: python3 -c 1\nexport const a = 1;\n"],
    ["a Kotlin string with no spawn", "T.kt", 'val label = "python3"\n'],
    ["a package.json description", "package.json", '{"description":"replaces the python3 tool","scripts":{"t":"bun test"}}\n'],
  ];
  for (const [what, path, text] of MENTIONS) {
    arm(what, "green", (r) => {
      w(r, path, text);
      commit(r);
    }, (r) => isTracked(r, path));
  }

  // ── the compat modules: a NAME is not an invocation ────────────────────────────────────────
  // tools/e2e/src/compat/python-{http,json,values}.ts exist so this repo does NOT shell into Python.
  // The red arm is the boundary: importing one is clean, importing one AND spawning python3 is not.
  arm("a .ts that only IMPORTS a compat module", "green", (r) => {
    w(r, "checks/x.ts", 'import { loads } from "../tools/e2e/src/compat/python-json.ts";\n');
    commit(r);
  }, (r) => isTracked(r, "checks/x.ts"));

  arm("a .ts that imports a compat module AND shells into python3", "red", (r) => {
    w(r, "checks/x.ts", 'import { loads } from "../tools/e2e/src/compat/python-json.ts";\nspawnSync("python3", ["-c", "1"]);\n');
    commit(r);
  }, (r) => isTracked(r, "checks/x.ts"));

  // console-next/src/ui/highlight.ts DISPLAYS Python (highlight.js's python grammar), which is neither
  // running nor teaching it. A real spawn in a file that also imports the grammar keeps its charge.
  const HIGHLIGHTER = [
    "import python from 'highlight.js/lib/languages/python';",
    "import sql from 'highlight.js/lib/languages/sql';",
    "const LANGUAGES = { bash, markdown, python, rust, sql };",
    "const EXTENSION = { py: 'python', rs: 'rust' };",
  ].join("\n") + "\n";
  arm("a .ts that only DISPLAYS Python through the highlighter's grammar", "green", (r) => {
    w(r, "checks/x.ts", HIGHLIGHTER);
    commit(r);
  }, (r) => isTracked(r, "checks/x.ts"));

  for (const [what, line] of [
    ["shells into python3", 'spawnSync("python3", ["-c", "1"]);'],
    ["shells into python", 'spawnSync("python", ["-c", "1"]);'],
    ["runs a python command line", "await Bun.$`python -c 1`;"],
  ] as const) {
    arm(`a .ts that displays Python through the highlighter AND ${what}`, "red", (r) => {
      w(r, "checks/x.ts", `${HIGHLIGHTER}${line}\n`);
      commit(r);
    }, (r) => isTracked(r, "checks/x.ts"));
  }

  test("invokesPython reads the file kind: the same words are an invocation in a script and prose in a README", () => {
    expect(invokesPython("run.sh", "python3 x\n")).toBe(true);
    expect(invokesPython("README.md", "python3 x\n")).toBe(false);
    expect(invokesPython("t.ts", "// python3 x\n")).toBe(false);
  });
});

describe("the no-python wall: callers", () => {
  // ── a caller that outlived the file it calls ───────────────────────────────────────────────
  arm("a .sh calling a script that does NOT exist", "red", (r) => {
    w(r, "run.sh", "bun checks/config/gone.ts check .\n");
    commit(r);
  }, (r) => existsSync(join(r, "run.sh")) && !existsSync(join(r, "checks", "config", "gone.ts")));

  arm("a .sh calling a script that DOES exist", "green", (r) => {
    w(r, "checks/exists.ts", "//\n");
    w(r, "run.sh", "bun checks/exists.ts\n");
    commit(r);
  }, (r) => existsSync(join(r, "checks", "exists.ts")));

  // V4-297: a QUOTED literal path, and a workflow, where CI's own `run:` lines call the gate's scripts.
  arm("a .sh calling a QUOTED path to a script that does NOT exist", "red", (r) => {
    w(r, "run.sh", 'bun "tools/gone.ts" check .\n');
    commit(r);
  }, (r) => existsSync(join(r, "run.sh")) && !existsSync(join(r, "tools", "gone.ts")));

  arm("a workflow calling a script that does NOT exist", "red", (r) => {
    w(r, ".github/workflows/ci.yml", "jobs:\n  gate:\n    steps:\n      - run: bun tools/gone.ts\n");
    commit(r);
  }, (r) => existsSync(join(r, ".github", "workflows", "ci.yml")) && !existsSync(join(r, "tools", "gone.ts")));

  arm("a workflow calling a script that DOES exist", "green", (r) => {
    w(r, "tools/here.ts", "//\n");
    w(r, ".github/workflows/ci.yml", "jobs:\n  gate:\n    steps:\n      - run: bun tools/here.ts\n");
    commit(r);
  }, (r) => existsSync(join(r, "tools", "here.ts")));

  arm("a workflow running a .ts with python3", "red", (r) => {
    w(r, "tools/here.ts", "//\n");
    w(r, ".github/workflows/ci.yml", "jobs:\n  gate:\n    steps:\n      - run: python3 tools/here.ts\n");
    commit(r);
  }, (r) => existsSync(join(r, "tools", "here.ts")));

  // ── a ledger instruction naming a file that is gone ────────────────────────────────────────
  // Graded ONLY on rows that have not run yet. The green arms are the boundary: without them the
  // obvious "any missing path is bad" implementation passes its red arm and then charges correct
  // ledger authorship.
  const ledger = (r: string, id: string, status: string, verify: string) =>
    w(r, ".dev/campaigns/c.toml", `[[items]]\nid = "${id}"\nstatus = "${status}"\nverify = "${verify}"\n`);

  arm("a TODO row whose verify names a missing .py", "red", (r) => {
    ledger(r, "T-1", "todo", "bun checks/gone.py");
    commit(r);
  }, (r) => !existsSync(join(r, "checks", "gone.py")));

  arm("a TODO row naming a .ts it will CREATE", "green", (r) => {
    ledger(r, "T-2", "todo", "bun checks/not-yet-written.ts");
    commit(r);
  }, (r) => !existsSync(join(r, "checks", "not-yet-written.ts")));

  arm("a VERIFIED row's verify is history, not an instruction", "green", (r) => {
    ledger(r, "T-3", "verified", "python3 checks/long-since-converted.py");
    commit(r);
  }, (r) => !existsSync(join(r, "checks", "long-since-converted.py")));

  arm("a TODO row naming the WRONG runtime for the extension", "red", (r) => {
    w(r, "checks/w.ts", "//\n");
    ledger(r, "T-4", "todo", "python3 checks/w.ts");
    commit(r);
  }, (r) => existsSync(join(r, "checks", "w.ts")));

  arm("a TODO row whose files= names a missing .py", "red", (r) => {
    w(r, ".dev/campaigns/c.toml", `[[items]]\nid = "T-5"\nstatus = "todo"\nfiles = ["checks/vanished.py"]\n`);
    commit(r);
  }, (r) => !existsSync(join(r, "checks", "vanished.py")));

  arm("a TODO row whose files= is a GLOB", "green", (r) => {
    w(r, ".dev/campaigns/c.toml", `[[items]]\nid = "T-6"\nstatus = "todo"\nfiles = ["checks/*.py"]\n`);
    commit(r);
  }, (r) => readFileSync(join(r, ".dev", "campaigns", "c.toml"), "utf8").includes('files = ["checks/*.py"]'));

  // A registry row whose wall= names a file that is gone.
  arm("a registry wall= naming a missing file", "red", (r) => {
    w(r, "walls/reg.toml", `[[law]]\ntag = "L1"\nwall = "walls/gone.py"\n`);
    commit(r);
  }, (r) => !existsSync(join(r, "walls", "gone.py")));

  arm("a registry wall= that is EMPTY is not charged", "green", (r) => {
    w(r, "walls/reg.toml", `[[law]]\ntag = "L2"\nwall = ""\n`);
    commit(r);
  }, (r) => readFileSync(join(r, "walls", "reg.toml"), "utf8").includes('wall = ""'));

  // ── a live caller running the WRONG runtime for the extension ──────────────────────────────
  // The red arm carries splice-builder2's ACTUAL slip, quotes and all: a paraphrased arm
  // (`python3 wall.ts`) passes against a regex blind to the only form it has ever had to catch.
  arm("a .sh running a .ts through python3, path QUOTED", "red", (r) => {
    w(r, "checks/mock_chat.ts", "//\n");
    w(r, "inside.sh", 'HERE=checks\npython3 "$HERE/mock_chat.ts" --port 8080 &\n');
    commit(r);
  }, (r) => readFileSync(join(r, "inside.sh"), "utf8").includes('python3 "$HERE/mock_chat.ts"'));

  arm("the same caller with the interpreter fixed", "green", (r) => {
    w(r, "checks/mock_chat.ts", "//\n");
    w(r, "inside.sh", 'HERE=checks\nbun "$HERE/mock_chat.ts" --port 8080 &\n');
    commit(r);
  }, (r) => readFileSync(join(r, "inside.sh"), "utf8").includes('bun "$HERE/mock_chat.ts"'));

  // THE ARM THAT LICENSES THE WHOLE CENSUS: comment lines are prose, or this would charge every
  // seat that documented the defect it fixed.
  arm("a COMMENT quoting the wrong-runtime form", "green", (r) => {
    w(r, "run.sh", '# was: python3 "$HERE/mock_chat.ts", fixed in this commit\nbun a.ts\n');
    w(r, "a.ts", "//\n");
    commit(r);
  }, (r) => readFileSync(join(r, "run.sh"), "utf8").startsWith("#"));

  test("mismatchedRuntimes names each crossed form and nothing else", () => {
    expect(mismatchedRuntimes('python3 "$HERE/m.ts" &')).toHaveLength(1);
    expect(mismatchedRuntimes("bun checks/x.py")).toHaveLength(1);
    expect(mismatchedRuntimes('bun "$HERE/m.ts" &')).toHaveLength(0);
    expect(mismatchedRuntimes('# was python3 "$HERE/m.ts"')).toHaveLength(0);
  });
});

// ─── the guard ─────────────────────────────────────────────────

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

function guardArm(label: string, expect_: "block" | "allow", payload: unknown) {
  test(`${label} -> ${expect_}`, () => {
    const rc = guard(payload);
    expect(rc === 2 ? "block" : "allow", `${label}: exit ${rc}`).toBe(expect_);
  });
}

describe("the no-python write guard", () => {
  // The caller file is only a PATH to the guard: it judges the text the call would put there.
  const caller = "tools/example/caller.sh";
  guardArm("any .py file", "block", write("checks/brand-new.py", "x = 1\n"));
  guardArm("a .ts that SHELLS into python3", "block", write("checks/newthing.ts", `spawnSync("python3", []);\n`));
  guardArm("a .ts whose COMMENT names python3", "allow", write("checks/newthing.ts", "// run python3 foo.py\n"));
  guardArm("a .ts that runs python through a Bun shell template", "block", write("checks/newthing.ts", "await Bun.$`python3 foo.py`;\n"));
  guardArm("a .sh that runs python3", "block", write("checks/newthing.sh", "#!/bin/sh\npython3 foo.py\n"));
  guardArm("a .md that teaches python3", "allow", write("docs/newthing.md", "run `python3 foo.py`\n"));
  guardArm("a clean .ts", "allow", write("checks/newthing.ts", "export const a = 1;\n"));
  guardArm("an unrelated edit to a caller", "allow", edit(caller, "echo hi\n"));
  guardArm("an edit that adds python3 to a caller", "block", edit(caller, "python3 x.py\n"));
  guardArm("the wall's own source is exempt", "allow", write("tools/gate/src/lib/no-python.ts", "python3\n"));
  guardArm("a path OUTSIDE the repo", "allow", { tool_name: "Write", tool_input: { file_path: "/tmp/scratch.py", content: "x = 1\n" } });
  guardArm("a non-write tool", "allow", { tool_name: "Bash", tool_input: { command: "python3 x.py" } });
  guardArm("MultiEdit smuggling it in a later edit", "block", {
    tool_name: "MultiEdit",
    tool_input: { file_path: `${repoRoot}/checks/newthing.ts`, edits: [{ new_string: "const a = 1;" }, { new_string: "spawnSync('python3', []);" }] },
  });
  guardArm("empty stdin is not a verdict", "allow", "");
  guardArm("unparseable stdin fails OPEN, never closed", "allow", "{not json");

  // THE WRITE-TIME HALF OF THE RUNTIME CENSUS: the wrong interpreter in front of a .ts.
  guardArm("the wrong runtime into a caller", "block", edit(caller, 'python3 "$HERE/mock_chat.ts" &'));
  guardArm("the same line with the interpreter fixed", "allow", edit(caller, 'bun "$HERE/mock_chat.ts" &'));
  guardArm("bun running a .py in a caller", "block", edit(caller, "bun checks/config/x.py\n"));
  guardArm("a comment quoting the wrong runtime", "allow", edit(caller, '# was python3 "$HERE/mock_chat.ts"\n'));
});
