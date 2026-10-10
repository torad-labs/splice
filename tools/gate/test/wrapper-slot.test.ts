// Red-green proof that the root `./gradlew` cannot start gradle outside the gradle slot.
//
// WHY THIS WALL EXISTS. The slot serialises every gradle in the checkout, but before this it only
// serialised the runs that TOOK it. On 2026-10-10 a seat ran three one-task checks straight through
// the wrapper beside a run inside the slot; two writers then shared one test-results directory and
// gradle's reporter closed onto a file the other had already moved, which ended the run with a
// NoSuchFileException naming an internal binary. A rule both seats already agreed on did not stop
// it, so the wrapper routes itself and running outside the slot is no longer something to remember.
//
// HOW IT IS MEASURED: through the REAL wrapper, never a copy of its logic. Each arm runs
// `<repo>/gradlew` in a throwaway tree with a fake `bun` first on PATH that records its own argv and
// exits 0. So an arm asserts what the shipped script decided, and a arm that passes because the test
// re-implemented the decision is not possible here.
//
// THE EXEMPT ARMS MATTER MORE THAN THE ROUTED ONES. A wrapper that routes everything hangs the seat
// running `./gradlew --stop` behind the build it is trying to stop, and adds a bun dependency to
// every CI step for no serialisation at all. Those arms are the ones that keep this fix from costing
// more than the bug.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

interface Run {
  /** The argv the fake bun was called with, or null when the wrapper never called it. */
  readonly routed: string[] | null;
  readonly stderr: string;
  readonly status: number;
}

/**
 * Run the real wrapper in a throwaway tree.
 *
 * The tree carries the wrapper and an empty `tools/gate/index.ts`, which is the only thing the guard
 * probes for to decide it is in a repository that has a slot. It carries NO gradle wrapper jar, so an
 * arm that is meant not to route fails somewhere after the guard — which is why these arms assert on
 * the receipt's absence and never on the exit status.
 */
function wrapper(args: readonly string[], env: Record<string, string> = {}): Run {
  const tree = mkdtempSync(join(tmpdir(), "splice-wrapper-"));
  try {
    copyFileSync(join(repoRoot, "gradlew"), join(tree, "gradlew"));
    mkdirSync(join(tree, "tools/gate"), { recursive: true });
    writeFileSync(join(tree, "tools/gate/index.ts"), "");
    const receipt = join(tree, "bun-argv");
    mkdirSync(join(tree, "bin"));
    // `"$@"` one per line: the arm reads the argv the wrapper built, not a re-joined string.
    writeFileSync(join(tree, "bin/bun"), `#!/bin/sh\nfor a in "$@"; do echo "$a"; done > "${receipt}"\n`, { mode: 0o755 });
    const ran = spawnSync("sh", [join(tree, "gradlew"), ...args], {
      cwd: tree,
      encoding: "utf8",
      // CI and the marker are cleared here so each arm sets only what it is measuring: inherited
      // either one would make every routed arm pass for the wrong reason.
      env: { ...process.env, PATH: `${join(tree, "bin")}:/usr/bin:/bin`, CI: "", SPLICE_GRADLE_SLOT: "", ...env },
    });
    const routed = existsSync(receipt) ? readFileSync(receipt, "utf8").trimEnd().split("\n") : null;
    return { routed, stderr: ran.stderr ?? "", status: ran.status ?? 1 };
  } finally {
    rmSync(tree, { recursive: true, force: true });
  }
}

describe("the root wrapper sends a build through the gradle slot", () => {
  test("a direct build routes, naming the slot command and keeping every argument", () => {
    const run = wrapper([":features-sessions:test", "--tests", "splice.sessions.http.Team*"]);
    expect(run.routed, "a direct ./gradlew build must re-execute through the slot").not.toBeNull();
    const argv = run.routed ?? [];
    // `bun <dir> slot <label> -- <args>`: the dir, the subcommand, a label, the boundary, then the build.
    expect(argv[1]).toBe("slot");
    expect(argv[3]).toBe("--");
    expect(argv.slice(4)).toEqual([":features-sessions:test", "--tests", "splice.sessions.http.Team*"]);
    // The label is what a waiting seat reads out of the holder file, so it has to identify the caller.
    expect(argv[2]).toMatch(/^gradlew-pid\d+$/);
  });

  test("a seat can name its own label, so the holder file says who to ask", () => {
    const run = wrapper(["check"], { GRADLE_SLOT_LABEL: "builder3" });
    expect(run.routed?.[2]).toBe("builder3");
  });

  test("a run already admitted by a live slot runs gradle itself, and does not route twice", () => {
    // The slot spawns this same wrapper, so believing the marker is what keeps that from recursing.
    const run = wrapper([":app:test"], { SPLICE_GRADLE_SLOT: `builder3:${process.pid}` });
    expect(run.routed, "a run inside the slot must not take the slot again").toBeNull();
  });

  test("a marker left behind by a finished run is ignored, so a stale export cannot open the door", () => {
    // A seat that exports the marker once would otherwise run outside the slot for the rest of its
    // session. The marker carries the slot process's pid, and a dead pid admits nothing.
    const dead = deadPid();
    const run = wrapper([":app:test"], { SPLICE_GRADLE_SLOT: `stale:${dead}` });
    expect(run.routed, `a marker naming the dead pid ${dead} must not be believed`).not.toBeNull();
  });

  test("CI is left alone: one build per runner has no second writer to collide with", () => {
    const run = wrapper([":app:shadowJar"], { CI: "true" });
    expect(run.routed, "routing CI would add a bun dependency to every workflow step").toBeNull();
  });

  test("a tree with no slot to take runs gradle directly", () => {
    // A detached worktree or an extracted source tarball has no tools/gate; the wrapper still works.
    const tree = mkdtempSync(join(tmpdir(), "splice-noslot-"));
    try {
      copyFileSync(join(repoRoot, "gradlew"), join(tree, "gradlew"));
      const receipt = join(tree, "bun-argv");
      mkdirSync(join(tree, "bin"));
      writeFileSync(join(tree, "bin/bun"), `#!/bin/sh\necho called > "${receipt}"\n`, { mode: 0o755 });
      spawnSync("sh", [join(tree, "gradlew"), ":app:test"], {
        cwd: tree,
        encoding: "utf8",
        env: { ...process.env, PATH: `${join(tree, "bin")}:/usr/bin:/bin`, CI: "", SPLICE_GRADLE_SLOT: "" },
      });
      expect(existsSync(receipt), "no tools/gate means no slot to route to").toBe(false);
    } finally {
      rmSync(tree, { recursive: true, force: true });
    }
  });

  // THE CLIENT-ONLY COMMANDS. Each builds nothing, so each must reach gradle without waiting for a
  // build to finish — `--stop` most of all, which a seat reaches for to stop the build it would queue
  // behind.
  for (const flag of ["--version", "-v", "--stop", "--status", "--help", "-h", "-?"]) {
    test(`${flag} takes no slot`, () => {
      expect(wrapper([flag]).routed, `${flag} builds nothing and must not queue`).toBeNull();
    });
  }

  test("no arguments takes no slot, because the slot refuses an empty task list", () => {
    // `./gradlew` alone is gradle's own help. Routed, it would come back as the slot's exit 2 refusal
    // to report a pass for a run that did nothing — a confusing answer to an innocent command.
    expect(wrapper([]).routed).toBeNull();
  });

  test("a build whose arguments merely contain a task named like a flag still routes", () => {
    // The exemption reads whole arguments, so `--stop` as a VALUE does not exempt the run.
    const run = wrapper([":app:test", "--tests", "StopDeadlineTest"]);
    expect(run.routed).not.toBeNull();
  });

  test("with no bun to take the slot, the wrapper refuses and names the way in", () => {
    const tree = mkdtempSync(join(tmpdir(), "splice-nobun-"));
    try {
      copyFileSync(join(repoRoot, "gradlew"), join(tree, "gradlew"));
      mkdirSync(join(tree, "tools/gate"), { recursive: true });
      writeFileSync(join(tree, "tools/gate/index.ts"), "");
      mkdirSync(join(tree, "bin"));
      const ran = spawnSync("sh", [join(tree, "gradlew"), ":app:test"], {
        cwd: tree,
        encoding: "utf8",
        env: { PATH: `${join(tree, "bin")}:/usr/bin:/bin`, HOME: tree, CI: "", SPLICE_GRADLE_SLOT: "" },
      });
      expect(ran.status, "a build that cannot take the slot must not run").not.toBe(0);
      // A refusal that does not say how to proceed is how a seat ends up going around the wall.
      expect(ran.stderr).toContain("bun tools/gate slot");
    } finally {
      rmSync(tree, { recursive: true, force: true });
    }
  });
});

/** A pid that is not running: the highest the kernel will hand out, which a fresh machine is far from. */
function deadPid(): number {
  const max = Number(readFileSync("/proc/sys/kernel/pid_max", "utf8").trim());
  for (let pid = max - 1; pid > max - 64; pid -= 1) {
    if (!existsSync(`/proc/${pid}`)) return pid;
  }
  throw new Error("every pid near pid_max is in use, so this arm cannot name a dead one");
}
