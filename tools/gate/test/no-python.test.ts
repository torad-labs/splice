// Red-green proof for the no-Python wall (src/lib/no-python.ts), both lifecycles.
//
// THE WALL ARMS grade fixture repositories in-process, one fixture ROOT per shape. Every arm asserts
// its SETUP before it grades: an arm that grades a mutation it did not make is the failure this whole
// wall family exists to catch. There is no list and no exemption, so every charge is a new one and every
// green arm is a file that names Python without running or installing it.
//
// THE SHAPE FIXTURES (fixtures/no-python-shapes.json) are an independent reviewer's probe of the wall: 53
// files, each with the verdict it must get, and two launcher configs added beside them. They were written
// against the previous text-matching wall, which let 36 of them through and charged 8 prose files; they
// stay as the standing proof that the structural reading does neither. Each is graded by the wall AND by
// the write guard. They live in a data file because a launcher entry written out inside this file would
// be a launcher entry of this file.
//
// THE GUARD ARMS drive `bun tools/gate no-python --guard` as a subprocess against THIS repository
// with synthetic PreToolUse events, exactly as .claude/settings.json runs it. THE ALLOW ARMS MATTER
// MORE THAN THE BLOCK ARMS: a write guard that over-refuses blocks the work it exists to allow, and
// the seat it blocks cannot route around it.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { WallError, enumerate, factsOf, guardVerdict, invokesPython, mismatchedCalls, wall } from "../src/lib/no-python.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

// ─── the wall ──────────────────────────────────────────────

function git(cwd: string, ...args: string[]) {
  const r = spawnSync("git", ["-C", cwd, ...args], { encoding: "utf8" });
  return { rc: r.status ?? 1, out: (r.stdout || "").trim(), err: (r.stderr || "").trim() };
}
const isTracked = (cwd: string, path: string) => enumerate(cwd).tracked.includes(path);

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

function fixtureRoot(): string {
  const root = mkdtempSync(join(tmpdir(), "nopy-"));
  git(root, "init", "-q");
  git(root, "config", "user.email", "t@t");
  git(root, "config", "user.name", "t");
  return root;
}

function arm(label: string, expect_: "red" | "green", build: (root: string) => void, setupOk: (root: string) => boolean) {
  test(`${label} -> ${expect_}`, () => {
    const root = fixtureRoot();
    try {
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
  mkdirSync(dirname(join(root, rel)), { recursive: true });
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
  }, (r) => isTracked(r, "sneaky.py"));

  arm("an EMPTY tracked .py", "red", (r) => {
    w(r, "empty.py", "");
    commit(r);
  }, (r) => isTracked(r, "empty.py"));

  arm("a tree with no Python at all", "green", (r) => {
    w(r, "a.ts", "export const a = 1;\n");
    commit(r);
  }, (r) => isTracked(r, "a.ts") && enumerate(r).all.every((f) => !f.endsWith(".py")));

  // A .py whose docstring names python3 is still a .py: counted by the file census, once.
  arm("a .py whose docstring names python3", "red", (r) => {
    w(r, "a.py", '"""run with python3 a.py"""\n');
    commit(r);
  }, (r) => isTracked(r, "a.py"));

  // `git ls-files` lists a tracked file deleted in the worktree but not yet staged; it exists nowhere.
  arm("a tracked .py DELETED but not yet staged", "green", (r) => {
    w(r, "a.py", "x\n");
    commit(r);
    rmSync(join(r, "a.py"));
  }, (r) => !existsSync(join(r, "a.py")) && !enumerate(r).all.includes("a.py"));

  arm("an UNTRACKED scratch .py in the worktree", "red", (r) => {
    w(r, "a.ts", "export const a = 1;\n");
    commit(r);
    w(r, "scratch.py", "import pathlib\n"); // written, never added
  }, (r) => enumerate(r).untracked.includes("scratch.py"));

  // The leg must charge the AUTHOR, never the package manager.
  arm("a .gitignore'd vendor .py is NOT charged", "green", (r) => {
    w(r, ".gitignore", "vendor/\n");
    w(r, "vendor/dep.py", "x\n");
    commit(r);
  }, (r) => existsSync(join(r, "vendor", "dep.py")) && !enumerate(r).all.includes("vendor/dep.py"));

  // ONE DOMAIN for every kind: an untracked script is judged as the tracked one would be, and an ignored one is not.
  arm("an UNTRACKED shell script that runs python3", "red", (r) => {
    w(r, "a.ts", "export const a = 1;\n");
    commit(r);
    w(r, "scratch.sh", "#!/bin/sh\npython3 -V\n");
  }, (r) => enumerate(r).untracked.includes("scratch.sh"));

  arm("an IGNORED shell script that runs python3", "green", (r) => {
    w(r, ".gitignore", "scratch.sh\n");
    w(r, "scratch.sh", "#!/bin/sh\npython3 -V\n");
    commit(r);
  }, (r) => existsSync(join(r, "scratch.sh")) && !enumerate(r).all.includes("scratch.sh"));

  arm("a tracked shell script that runs python3, DELETED but not staged", "green", (r) => {
    w(r, "gone.sh", "#!/bin/sh\npython3 -V\n");
    commit(r);
    rmSync(join(r, "gone.sh"));
  }, (r) => !existsSync(join(r, "gone.sh")));

  // A path git would quote ("\303\251") is read by its real name, not skipped as unreadable.
  arm("a script under a non-ASCII directory that runs python3", "red", (r) => {
    w(r, "é/run.sh", "#!/bin/sh\npython3 -V\n");
    commit(r);
  }, (r) => enumerate(r).all.includes("é/run.sh"));

  // A tracked file the wall cannot read fails the wall BY NAME. Root reads anything, so the arm needs a user.
  test.skipIf(process.getuid?.() === 0)("an unreadable tracked file fails the wall and names it", () => {
    const r = fixtureRoot();
    try {
      w(r, "locked.txt", "x\n");
      commit(r);
      chmodSync(join(r, "locked.txt"), 0o000);
      expect(() => wall(r)).toThrow(/locked\.txt/);
    } finally {
      chmodSync(join(r, "locked.txt"), 0o644);
      rmSync(r, { recursive: true, force: true });
    }
  });
});

describe("the no-python wall: invocations by structure", () => {
  // Every RED arm is a way the repo would actually run or install Python; every GREEN arm names it
  // without running anything.
  const SPAWN = 'import { spawnSync } from "node:child_process";\n';
  const SHAPES: readonly (readonly [string, string, string])[] = [
    ["a shebang", "tool", "#!/usr/bin/env python3\nprint(1)\n"],
    ["a shell command", "run.sh", "#!/bin/sh\npython3 -c 'print(1)'\n"],
    ["a shell command after &&", "run.sh", "#!/bin/sh\ncd x && python3 -m http.server\n"],
    ["a shell command with env words first", "run.sh", "#!/bin/sh\nFOO=1 python -c 1\n"],
    ["a command substitution", "run.sh", '#!/bin/sh\nV="$(python3 -V)"\n'],
    ["a command behind env and nice", "run.sh", "#!/bin/sh\nenv -u HOME nice -n 5 python3 -V\n"],
    ["a command inside sh -c", "run.sh", '#!/bin/sh\nbash -lc "cd x && python3 -V"\n'],
    ["a command inside eval", "run.sh", '#!/bin/sh\neval "python3 -V"\n'],
    ["pip", "run.sh", "#!/bin/sh\npip install requests\n"],
    ["a package-manager install of the interpreter", "run.sh", "#!/bin/sh\napt-get install -y curl python3\n"],
    ["a Dockerfile RUN", "docker/Dockerfile", "FROM debian\nRUN python3 -m venv /v\n"],
    ["a Dockerfile python image", "docker/Dockerfile", "FROM python:3.12-slim\n"],
    ["a Dockerfile package install", "docker/Dockerfile", "FROM debian\nRUN apt-get install -y python3 curl\n"],
    ["a Dockerfile continued RUN", "docker/Dockerfile", "FROM debian\nRUN apt-get update \\\n && python3 -V\n"],
    ["a workflow run step", ".github/workflows/ci.yml", "jobs:\n  a:\n    steps:\n      - run: python3 x.sh\n"],
    ["a workflow multi-line run block", ".github/workflows/ci.yml", "jobs:\n  a:\n    steps:\n      - run: |\n          echo hi\n          python3 x.sh\n"],
    ["a workflow setup-python step", ".github/workflows/ci.yml", "jobs:\n  a:\n    steps:\n      - uses: actions/setup-python@v5\n"],
    ["a package.json script", "package.json", '{"scripts":{"t":"python3 -m pytest"}}\n'],
    ["a Kotlin process spawn", "T.kt", 'val p = ProcessBuilder("python3", "-c", "pass").start()\n'],
    ["a Kotlin spawn through a list", "T.kt", 'val p = ProcessBuilder(listOf("python3", "-c", "pass")).start()\n'],
    ["a Java process spawn", "T.java", 'class T { void f() throws Exception { new ProcessBuilder("python3", "-V").start(); } }\n'],
    ["a Gradle exec", "build.gradle.kts", 'tasks.register<Exec>("x") { commandLine("python3", "x.py") }\n'],
    ["a TS process spawn", "t.ts", `${SPAWN}spawnSync("python", ["-c", "1"]);\n`],
    ["a TS spawn through a constant", "t.ts", `${SPAWN}const PROGRAM = "python3";\nspawnSync(PROGRAM, ["-V"]);\n`],
    ["a TS spawn through a namespace import", "t.ts", `import * as cp from "node:child_process";\ncp.spawnSync("python3", ["-V"]);\n`],
    ["a TS spawn through require", "t.js", `const { spawnSync: launch } = require("child_process");\nlaunch("python3", ["-V"]);\n`],
    ["a Bun.spawn argv", "t.ts", 'Bun.spawn(["python3", "-V"]);\n'],
    ["a Bun.spawn cmd object", "t.ts", 'Bun.spawn({ cmd: ["python3", "-V"] });\n'],
    ["a Bun shell template", "t.ts", "await Bun.$`python3 -c 1`;\n"],
    ["a launcher object literal", "t.ts", 'export const server = { command: "python3", args: ["s.py"] };\n'],
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
    ["a shell comment", "run.sh", "#!/bin/sh\n# python3 used to run this\necho ok\n"],
    ["a shell echo", "run.sh", '#!/bin/sh\necho "no python3 here"\n'],
    ["a shell lookup that runs nothing", "run.sh", "#!/bin/sh\ncommand -v python3 >/dev/null || echo none\n"],
    ["a shell heredoc that prints a command", "run.sh", "#!/bin/sh\ncat <<EOF\npython3 -V\nEOF\n"],
    ["a TS comment", "t.ts", "// run it with: python3 -c 1\nexport const a = 1;\n"],
    ["a TS string next to an unrelated spawn", "t.ts", `${SPAWN}const label = "python3";\nspawnSync("git", ["status"]);\n`],
    ["a TS template that documents a spawn", "t.ts", "const doc = `\nspawnSync(\"python3\", [\"-V\"]);\n`;\n"],
    ["a Kotlin string with no spawn", "T.kt", 'val label = "python3"\n'],
    ["a Kotlin spawn of another program beside the word", "T.kt", 'val label = "python3"\nval p = ProcessBuilder("git", "status")\n'],
    ["a package.json description", "package.json", '{"description":"replaces the python3 tool","scripts":{"t":"bun test"}}\n'],
    ["a workflow step named for the action", ".github/workflows/ci.yml", 'jobs:\n  a:\n    steps:\n      - name: "Port actions/setup-python docs"\n        run: echo ok\n'],
    ["a Dockerfile label", "Dockerfile", 'FROM debian\nLABEL description="run: python3 -V"\n'],
  ];
  for (const [what, path, text] of MENTIONS) {
    arm(what, "green", (r) => {
      w(r, path, text);
      commit(r);
    }, (r) => isTracked(r, path));
  }

  // ── the one named exemption: a Dockerfile STAGE, never the file ─────────────────────────
  const STAGES = (fresh: string, mark: string, upgrade: string) =>
    `FROM debian AS fresh\n${fresh}${mark}FROM fresh AS upgrade\n${upgrade}`;
  const MARK = "# NO-PYTHON-EXEMPT[2026-10-10]: runs a published 0.3.x as shipped (test)\n";
  const PY = "RUN apt-get install -y python3\n";
  const dockerArm = (label: string, expect_: "red" | "green", text: string) =>
    arm(label, expect_, (r) => {
      w(r, "docker/Dockerfile", text);
      commit(r);
    }, (r) => isTracked(r, "docker/Dockerfile"));
  dockerArm("python in the marked stage only", "green", STAGES("RUN echo ok\n", MARK, PY));
  dockerArm("python in the stage BEFORE the marked one", "red", STAGES(PY, MARK, "RUN echo ok\n"));
  dockerArm("python in a stage AFTER the marked one", "red", STAGES("RUN echo ok\n", MARK, "RUN echo ok\n") + "FROM debian AS later\n" + PY);
  dockerArm("python in the same file with no marker", "red", STAGES("RUN echo ok\n", "", PY));
  dockerArm("a marker with no reason", "red", STAGES("RUN echo ok\n", "# NO-PYTHON-EXEMPT[2026-10-10]:\n", PY));
  dockerArm("a marker with no date", "red", STAGES("RUN echo ok\n", "# NO-PYTHON-EXEMPT: a reason\n", PY));
  dockerArm("a marker above an unrelated instruction, not the FROM", "red", STAGES("RUN echo ok\n", "", "") + MARK + "RUN echo x\n" + "FROM debian AS later\n" + PY);

  // ── the compat modules: a NAME is not an invocation ────────────────────────────────────────
  // tools/e2e/src/compat/python-{http,json,values}.ts exist so this repo does NOT shell into Python.
  // The red arm is the boundary: importing one is clean, importing one AND spawning python3 is not.
  arm("a .ts that only IMPORTS a compat module", "green", (r) => {
    w(r, "checks/x.ts", 'import { loads } from "../tools/e2e/src/compat/python-json.ts";\n');
    commit(r);
  }, (r) => isTracked(r, "checks/x.ts"));

  arm("a .ts that imports a compat module AND shells into python3", "red", (r) => {
    w(r, "checks/x.ts", `import { loads } from "../tools/e2e/src/compat/python-json.ts";\n${SPAWN}spawnSync("python3", ["-c", "1"]);\n`);
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
      w(r, "checks/x.ts", `${SPAWN}${HIGHLIGHTER}${line}\n`);
      commit(r);
    }, (r) => isTracked(r, "checks/x.ts"));
  }

  // THIS LIBRARY AND ITS TESTS ARE JUDGED LIKE ANY OTHER FILE: a real spawn at the three paths the wall once
  // exempted is charged, because a law that exempts its declaration site can be broken from there.
  for (const path of ["tools/gate/src/lib/no-python.ts", "tools/gate/src/commands/no-python.ts", "tools/gate/test/no-python.test.ts"]) {
    arm(`a real python3 spawn in ${path}`, "red", (r) => {
      w(r, path, `${SPAWN}spawnSync("python3", ["-V"]);\n`);
      commit(r);
    }, (r) => isTracked(r, path));
  }

  test("invokesPython reads the file kind: the same words are an invocation in a script and prose in a README", () => {
    expect(invokesPython("run.sh", "python3 x\n")).toBe(true);
    expect(invokesPython("README.md", "python3 x\n")).toBe(false);
    expect(invokesPython("t.ts", "// python3 x\n")).toBe(false);
  });
});

// ─── the shape fixtures ────────────────────────────────────

interface Shape {
  id: string;
  category: string;
  path: string;
  text: string;
  tracked: boolean;
  expected: boolean;
}
const SHAPES_JSON = JSON.parse(readFileSync(join(import.meta.dir, "fixtures", "no-python-shapes.json"), "utf8")) as Shape[];

describe("the no-python wall: the reviewer's shape fixtures", () => {
  for (const s of SHAPES_JSON) {
    test(`${s.id}: the wall is ${s.expected ? "RED" : "GREEN"} and so is the guard`, () => {
      const root = fixtureRoot();
      try {
        w(root, s.path, s.text);
        if (s.tracked) git(root, "add", "-f", "--", s.path);
        expect(enumerate(root).all.includes(s.path), `${s.id}: SETUP — the fixture is not in the wall's domain`).toBe(true);
        expect(enumerate(root).tracked.includes(s.path)).toBe(s.tracked);
        const { red, detail } = verdict(root);
        expect(red, `${s.id}: wall ${red ? "RED" : "GREEN"}\n${detail}`).toBe(s.expected);
        const refused = guardVerdict(root, { tool_name: "Write", tool_input: { file_path: join(root, s.path), content: s.text } });
        expect(refused !== null, `${s.id}: guard ${refused === null ? "allowed" : "refused"}`).toBe(s.expected);
      } finally {
        rmSync(root, { recursive: true, force: true });
      }
    });
  }
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

  // A comment and an echo that NAME a removed script run nothing, so there is no call to dangle.
  arm("a shell COMMENT naming a script that no longer exists", "green", (r) => {
    w(r, "run.sh", "#!/bin/sh\n# previously python3 archived-example.py\necho ok\n");
    commit(r);
  }, (r) => !existsSync(join(r, "archived-example.py")));

  arm("a shell ECHO naming a script that no longer exists", "green", (r) => {
    w(r, "run.sh", '#!/bin/sh\necho "python3 archived-example.py"\n');
    commit(r);
  }, (r) => !existsSync(join(r, "archived-example.py")));

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
  // (`python3 wall.ts`) passes against a reading blind to the only form it has ever had to catch.
  arm("a .sh running a .ts through python3, path QUOTED", "red", (r) => {
    w(r, "checks/mock_chat.ts", "//\n");
    w(r, "inside.sh", 'HERE=checks\npython3 "checks/mock_chat.ts" --port 8080 &\n');
    commit(r);
  }, (r) => readFileSync(join(r, "inside.sh"), "utf8").includes('python3 "checks/mock_chat.ts"'));

  arm("the same caller with the interpreter fixed", "green", (r) => {
    w(r, "checks/mock_chat.ts", "//\n");
    w(r, "inside.sh", 'HERE=checks\nbun "checks/mock_chat.ts" --port 8080 &\n');
    commit(r);
  }, (r) => readFileSync(join(r, "inside.sh"), "utf8").includes('bun "checks/mock_chat.ts"'));

  // THE ARM THAT LICENSES THE WHOLE CENSUS: comment lines are prose, or this would charge every
  // seat that documented the defect it fixed.
  arm("a COMMENT quoting the wrong-runtime form", "green", (r) => {
    w(r, "run.sh", '# was: python3 "$HERE/mock_chat.ts", fixed in this commit\nbun a.ts\n');
    w(r, "a.ts", "//\n");
    commit(r);
  }, (r) => readFileSync(join(r, "run.sh"), "utf8").startsWith("#"));

  test("mismatchedCalls names each crossed form and nothing else", () => {
    const crossed = (path: string, text: string) => mismatchedCalls(factsOf(repoRoot, [{ path, text }]).get(path)!.calls);
    expect(crossed("run.sh", 'python3 "m.ts" &')).toHaveLength(1);
    expect(crossed("run.sh", "bun checks/x.py")).toHaveLength(1);
    expect(crossed("run.sh", 'bun "m.ts" &')).toHaveLength(0);
    expect(crossed("run.sh", '# was python3 "m.ts"')).toHaveLength(0);
  });
});

// ─── the guard ─────────────────────────────────────────────────

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
  const spawnImport = 'import { spawnSync } from "node:child_process";\n';
  guardArm("any .py file", "block", write("checks/brand-new.py", "x = 1\n"));
  guardArm("a .ts that SHELLS into python3", "block", write("checks/newthing.ts", `${spawnImport}spawnSync("python3", []);\n`));
  guardArm("a .ts whose COMMENT names python3", "allow", write("checks/newthing.ts", "// run python3 foo.py\n"));
  guardArm("a .ts that runs python through a Bun shell template", "block", write("checks/newthing.ts", "await Bun.$`python3 foo.py`;\n"));
  guardArm("a .sh that runs python3", "block", write("checks/newthing.sh", "#!/bin/sh\npython3 foo.py\n"));
  guardArm("a .md that teaches python3", "allow", write("docs/newthing.md", "run `python3 foo.py`\n"));
  guardArm("a clean .ts", "allow", write("checks/newthing.ts", "export const a = 1;\n"));
  guardArm("an unrelated edit to a caller", "allow", edit(caller, "echo hi\n"));
  guardArm("an edit that adds python3 to a caller", "block", edit(caller, "python3 x.py\n"));
  guardArm("the wall's own source is judged like any file", "block", write("tools/gate/src/lib/no-python.ts", `${spawnImport}spawnSync("python3", []);\n`));
  guardArm("the wall's own source may still name it", "allow", write("tools/gate/src/lib/no-python.ts", "// the pattern python3 is matched here\n"));
  guardArm("a path OUTSIDE the repo", "allow", { tool_name: "Write", tool_input: { file_path: "/tmp/scratch.py", content: "x = 1\n" } });
  guardArm("a non-write tool", "allow", { tool_name: "Bash", tool_input: { cmd: "python3 x.py" } });
  guardArm("MultiEdit smuggling it in a later edit", "block", {
    tool_name: "MultiEdit",
    tool_input: { file_path: `${repoRoot}/checks/newthing.ts`, edits: [{ new_string: spawnImport }, { new_string: "spawnSync('python3', []);" }] },
  });
  guardArm("empty stdin is not a verdict", "allow", "");
  guardArm("unparseable stdin fails OPEN, never closed", "allow", "{not json");

  // An Edit's fragment is not a whole document; the guard reads the part it can.
  guardArm("a workflow fragment that adds a python3 step", "block", edit(".github/workflows/ci.yml", "      - run: python3 x.sh\n"));

  // THE WRITE-TIME HALF OF THE RUNTIME CENSUS: the wrong interpreter in front of a .ts.
  guardArm("the wrong runtime into a caller", "block", edit(caller, 'python3 "$HERE/mock_chat.ts" &'));
  guardArm("the same line with the interpreter fixed", "allow", edit(caller, 'bun "$HERE/mock_chat.ts" &'));
  guardArm("bun running a .py in a caller", "block", edit(caller, "bun checks/config/x.py\n"));
  guardArm("a comment quoting the wrong runtime", "allow", edit(caller, '# was python3 "$HERE/mock_chat.ts"\n'));
});
