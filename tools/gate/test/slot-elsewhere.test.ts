// Where a gradle run builds once `rig` is on PATH: on another machine, or nowhere when none takes it.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { machine as arch, tmpdir, type } from "node:os";
import { join } from "node:path";
import { noMachineFor } from "../src/lib/elsewhere.ts";
import { runUnderSlot } from "../src/lib/slot.ts";

const folders: string[] = [];
afterAll(() => {
  for (const dir of folders) rmSync(dir, { recursive: true, force: true });
});

/** A build root whose gradlew leaves a receipt when it builds here, and a `rig` on PATH that records what it was asked,
 *  writes the status line it is given to the descriptor --status-fd names, and exits as it is told. */
function machine() {
  const dir = mkdtempSync(join(tmpdir(), "gate-elsewhere-test-"));
  folders.push(dir);
  const receipt = join(dir, "built-here");
  writeFileSync(join(dir, "gradlew"), `#!/usr/bin/env bash\necho "$*" > "${receipt}"\nexit 0\n`);
  chmodSync(join(dir, "gradlew"), 0o755);
  // the run there is the gate's slot, so the tree carries the gate
  mkdirSync(join(dir, "tools", "gate"), { recursive: true });
  writeFileSync(join(dir, "tools", "gate", "index.ts"), "");
  const bin = join(dir, "bin");
  const asked = join(dir, "rig-asked");
  mkdirSync(bin);
  writeFileSync(
    join(bin, "rig"),
    [
      "#!/usr/bin/env bash",
      `printf '%s\\n' "$@" > "${asked}"`,
      "fd=",
      'while [ $# -gt 0 ]; do case "$1" in --status-fd) fd=$2; shift 2;; --) break;; *) shift;; esac; done',
      'if [ -n "${RIG_STATUS:-}" ] && [ -n "$fd" ]; then eval "printf \'%s\\n\' \\"\\$RIG_STATUS\\" >&$fd"; fi',
      'exit "${RIG_EXIT:-0}"',
      "",
    ].join("\n"),
  );
  chmodSync(join(bin, "rig"), 0o755);
  const layout = { repoRoot: dir, buildRoot: dir };
  const envOf = (env: Record<string, string | undefined>) => ({
    PATH: `${bin}:/usr/bin:/bin`,
    CI: undefined,
    SPLICE_GRADLE_HERE: undefined,
    GRADLE_SLOT_LOCK: join(dir, ".gradle-slot.lock"),
    ...env,
  });
  const run = (args: string[], env: Record<string, string | undefined>) =>
    runUnderSlot({ layout, label: "elsewhere-test", args, env: envOf(env) });
  /** What the pre-push hook asks before its tier starts: why no machine is there to take it. */
  const absent = (env: Record<string, string | undefined>) =>
    noMachineFor({ buildRoot: dir, label: "pre-push", args: [], env: envOf(env) });
  return {
    run,
    absent,
    builtHere: () => existsSync(receipt),
    rigAsked: () => (existsSync(asked) ? readFileSync(asked, "utf8").trim().split("\n") : null),
  };
}

describe("a gradle run with rig on PATH", () => {
  // Nov 21, 2026: this place held "ordinary runs build here without asking the remote runner", which pinned a run that
  // went away only when SPLICE_GRADLE_REMOTE=1 asked. That switch is gone, and so is its test.
  test("a run goes to another machine with nothing set to ask for it", async () => {
    const m = machine();
    expect(await m.run([":app:test"], { RIG_STATUS: '{"exit":0}', RIG_EXIT: "0" })).toBe(0);
    expect(m.builtHere()).toBe(false);
    expect(m.rigAsked()).not.toBeNull();
  });

  test("with no rig on PATH the run builds here", async () => {
    const m = machine();
    expect(await m.run([":app:test"], { PATH: "/usr/bin:/bin" })).toBe(0);
    expect(m.builtHere()).toBe(true);
    expect(m.rigAsked()).toBeNull();
  });

  test("asked before the pre-push tier starts, rig's word for a machine that is not there comes back, and nothing builds", async () => {
    for (const why of ["unreachable", "no machine"]) {
      const m = machine();
      expect(await m.absent({ RIG_STATUS: `{"exit":null,"why":"${why}"}`, RIG_EXIT: "75" })).toBe(`rig: ${why}`);
      expect(m.builtHere()).toBe(false);
      const asked = m.rigAsked()!;
      // nothing is run there: the question is whether a machine of this system takes the tree
      expect(asked[asked.indexOf("--needs") + 1]).toBe(`system=${type()}/${arch()}`);
      expect(asked.slice(asked.indexOf("--") + 1)).toEqual(["true"]);
    }
  });

  test("a machine that answers, one with no room, a refusal and a silent rig all count as a machine being there", async () => {
    for (const env of [
      { RIG_STATUS: '{"exit":0}', RIG_EXIT: "0" },
      { RIG_STATUS: '{"exit":null,"why":"no room"}', RIG_EXIT: "75" },
      { RIG_STATUS: '{"exit":null,"why":"tree busy"}', RIG_EXIT: "75" },
      { RIG_STATUS: '{"exit":null,"why":"scan refused"}', RIG_EXIT: "75" },
      { RIG_EXIT: "64" },
    ]) {
      expect(await machine().absent(env)).toBeUndefined();
    }
  });

  test("where the tier builds in place (no rig on PATH, CI, SPLICE_GRADLE_HERE), rig is not asked", async () => {
    for (const env of [{ PATH: "/usr/bin:/bin" }, { CI: "true" }, { SPLICE_GRADLE_HERE: "1" }]) {
      const m = machine();
      expect(await m.absent({ RIG_STATUS: '{"exit":null,"why":"no machine"}', RIG_EXIT: "75", ...env })).toBeUndefined();
      expect(m.rigAsked()).toBeNull();
    }
  });

  test("another machine's run is the verdict, and nothing builds here", async () => {
    const m = machine();
    const status = await m.run([":app:test"], { RIG_STATUS: '{"host":"h","exit":3,"run_s":1}', RIG_EXIT: "3" });
    expect(status).toBe(3);
    expect(m.builtHere()).toBe(false);
  });

  test("the run there is this slot, with the same label and tasks, told to build where it stands", async () => {
    const m = machine();
    await m.run([":app:test", "--tests", "splice.X"], { RIG_STATUS: '{"exit":0}', RIG_EXIT: "0" });
    const asked = m.rigAsked()!;
    expect(asked.slice(0, 2)).toEqual(["remote", "run"]);
    expect(asked[asked.indexOf("--env") + 1]).toBe("SPLICE_GRADLE_HERE");
    expect(asked.slice(asked.indexOf("--") + 1)).toEqual([
      "bun", "tools/gate", "slot", "elsewhere-test", "--", ":app:test", "--tests", "splice.X",
    ]);
  });

  test("a run that tests goes to a machine of this one's system, so its verdict is the one it would have here", async () => {
    const m = machine();
    await m.run(["-q", ":app:test"], { RIG_STATUS: '{"exit":0}', RIG_EXIT: "0" });
    const asked = m.rigAsked()!;
    expect(asked[asked.indexOf("--needs") + 1]).toBe(`system=${type()}/${arch()}`);
  });

  test("no machine with room once the slot's wait is over: the run fails 75, and nothing builds here", async () => {
    const m = machine();
    const status = await m.run([":app:test"], {
      RIG_STATUS: '{"exit":null,"why":"no machine"}', RIG_EXIT: "75", GRADLE_SLOT_WAIT_S: "0",
    });
    expect(status).toBe(75);
    expect(m.builtHere()).toBe(false);
  });

  test("a tree rig refuses fails at once, and nothing builds here", async () => {
    const m = machine();
    const status = await m.run([":app:test"], { RIG_STATUS: '{"exit":null,"why":"scan refused"}', RIG_EXIT: "75" });
    expect(status).toBe(75);
    expect(m.builtHere()).toBe(false);
  });

  test("a rig that says nothing of a run (one too old to pick a machine) fails, and nothing builds here", async () => {
    const m = machine();
    expect(await m.run([":app:test"], { RIG_EXIT: "64" })).toBe(75);
    expect(m.builtHere()).toBe(false);
  });

  test("the fat jar is built there and rig is asked to bring it back", async () => {
    const m = machine();
    expect(await m.run([":app:shadowJar", "--quiet"], { RIG_STATUS: '{"exit":0}', RIG_EXIT: "0" })).toBe(0);
    expect(m.builtHere()).toBe(false);
    const asked = m.rigAsked()!;
    expect(asked[asked.indexOf("--fetch") + 1]).toBe("app/build/libs/app-all.jar");
    // any machine may take it, and it is told to pack every platform's engine, so the jar is the same whichever
    // system builds it. Nov 21, 2026: told nothing, a Mac built a jar with no engine for the Linux machine it came
    // back to.
    expect(asked).not.toContain("--needs");
    expect(asked.slice(asked.indexOf("--") + 1)).toEqual([
      "bun", "tools/gate", "slot", "elsewhere-test", "--", ":app:shadowJar", "--quiet", "-Psplice.allPlatforms=true",
    ]);
  });

  test("a jar build that says which platforms it packs is sent as it was asked", async () => {
    const m = machine();
    await m.run([":app:shadowJar", "-Psplice.allPlatforms=false"], { RIG_STATUS: '{"exit":0}', RIG_EXIT: "0" });
    const asked = m.rigAsked()!;
    expect(asked.slice(asked.indexOf("--") + 1)).toEqual([
      "bun", "tools/gate", "slot", "elsewhere-test", "--", ":app:shadowJar", "-Psplice.allPlatforms=false",
    ]);
  });

  test("a run that tests is not told which platforms to pack", async () => {
    const m = machine();
    await m.run([":app:test"], { RIG_STATUS: '{"exit":0}', RIG_EXIT: "0" });
    expect(m.rigAsked()!.some((arg) => arg.startsWith("-Psplice.allPlatforms"))).toBe(false);
  });

  test("a task that makes a file this machine reads, with no path to bring back, builds here", async () => {
    const m = machine();
    expect(await m.run([":app:installDist"], { RIG_STATUS: '{"exit":0}' })).toBe(0);
    expect(m.builtHere()).toBe(true);
    expect(m.rigAsked()).toBeNull();
  });

  test("a run that names a path on this machine builds here, and rig is never asked", async () => {
    const m = machine();
    expect(await m.run(["-p", "/tmp/some-project", "test"], { RIG_STATUS: '{"exit":0}' })).toBe(0);
    expect(await m.run(["--init-script=/tmp/ci.init.gradle", "test"], { RIG_STATUS: '{"exit":0}' })).toBe(0);
    expect(m.builtHere()).toBe(true);
    expect(m.rigAsked()).toBeNull();
  });

  test("SPLICE_GRADLE_HERE keeps the run on this machine", async () => {
    const m = machine();
    expect(await m.run([":app:test"], { SPLICE_GRADLE_HERE: "1", RIG_STATUS: '{"exit":0}' })).toBe(0);
    expect(m.builtHere()).toBe(true);
    expect(m.rigAsked()).toBeNull();
  });
});
