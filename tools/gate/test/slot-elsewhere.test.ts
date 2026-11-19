// Where a gradle run builds once `rig` is on PATH: on another machine, or nowhere when none takes it.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
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
  const run = (args: string[], env: Record<string, string | undefined>) =>
    runUnderSlot({
      layout,
      label: "elsewhere-test",
      args,
      env: {
        PATH: `${bin}:/usr/bin:/bin`,
        CI: undefined,
        SPLICE_GRADLE_HERE: undefined,
        GRADLE_SLOT_LOCK: join(dir, ".gradle-slot.lock"),
        ...env,
      },
    });
  return {
    run,
    builtHere: () => existsSync(receipt),
    rigAsked: () => (existsSync(asked) ? readFileSync(asked, "utf8").trim().split("\n") : null),
  };
}

describe("a gradle run with rig on PATH", () => {
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
    expect(asked.slice(asked.indexOf("--") + 1)).toEqual([
      "bun", "tools/gate", "slot", "elsewhere-test", "--", ":app:shadowJar", "--quiet",
    ]);
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
