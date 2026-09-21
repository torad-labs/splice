// The slot's contract, and the one property that makes the port safe to land beside the shell
// script it ports: both take flock(2) on the SAME path, so they can never both hold the slot.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { isoSeconds, lockPath, NO_TASKS_EXIT, runUnderSlot, SLOT_TIMEOUT_EXIT } from "../src/lib/slot.ts";
import { takeExclusive } from "../src/lib/flock.ts";
import { layout } from "../src/lib/repo.ts";

const real = layout();
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

/** A build root whose `gradlew` records how it was called — and what the holder file said. */
function fakeBuildRoot(script = 'echo "ARGS:$*"\ncat "$LOCK.holder"\nexit 0\n') {
  const dir = mkdtempSync(join(tmpdir(), "gate-slot-"));
  workspaces.push(dir);
  const receipt = join(dir, "receipt.txt");
  writeFileSync(
    join(dir, "gradlew"),
    `#!/usr/bin/env bash\nLOCK="${join(dir, ".gradle-slot.lock")}"\n{ ${script} } >"${receipt}" 2>&1\n`,
  );
  chmodSync(join(dir, "gradlew"), 0o755);
  // PATH without this machine's ~/.local/bin, so `command -v buildgate` finds nothing and the test
  // exercises the same branch CI does. The buildgate branch has a test of its own below.
  return { layout: { repoRoot: dir, buildRoot: dir }, dir, receipt, path: "/usr/bin:/bin" };
}

describe("the gradle slot", () => {
  test("the lock path is the one checks/gradle-slot.sh computes", () => {
    const script = readFileSync(join(real.repoRoot, "checks", "gradle-slot.sh"), "utf8");
    const line = /^LOCK="\$\{GRADLE_SLOT_LOCK:-\$ROOT(\/[^"}]+)\}"$/m.exec(script);
    expect(line, "gradle-slot.sh must still spell its default lock path the way this test reads it").not.toBeNull();
    expect(lockPath(real, {})).toBe(real.repoRoot + line![1]!);
    // and the CLI derives it from the build root rather than the literal `gateway`, so it follows
    // the build root when the restructure moves it to the repository root
    expect(lockPath(real, {})).toBe(join(real.buildRoot, ".gradle-slot.lock"));
  });

  test("GRADLE_SLOT_LOCK overrides it, as in the script", () => {
    expect(lockPath(real, { GRADLE_SLOT_LOCK: "/tmp/elsewhere.lock" })).toBe("/tmp/elsewhere.lock");
  });

  test("an EMPTY task list is DID NOT RUN, never PASSED", async () => {
    const fake = fakeBuildRoot();
    expect(await runUnderSlot({ layout: fake.layout, label: "empty", args: [] })).toBe(NO_TASKS_EXIT);
    expect(existsSync(fake.receipt)).toBe(false);
    expect(existsSync(join(fake.dir, ".gradle-slot.lock"))).toBe(false);
  });

  test("the holder is written BEFORE the JVM starts, and removed on exit", async () => {
    const fake = fakeBuildRoot();
    const code = await runUnderSlot({ layout: fake.layout, label: "V4-108", args: [":app:test"], env: { CI: "1", PATH: fake.path } });
    expect(code).toBe(0);
    const receipt = readFileSync(fake.receipt, "utf8");
    expect(receipt).toContain("ARGS:--no-daemon :app:test");
    expect(receipt).toMatch(/V4-108 pid=\d+ since=\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}/);
    expect(existsSync(join(fake.dir, ".gradle-slot.lock.holder"))).toBe(false);
  });

  test("gradle's exit status propagates", async () => {
    const fake = fakeBuildRoot("exit 7\n");
    expect(await runUnderSlot({ layout: fake.layout, label: "red", args: ["check"], env: { CI: "1", PATH: fake.path } })).toBe(7);
  });

  test("--offline is a local nicety and is dropped on CI", async () => {
    const local = fakeBuildRoot();
    await runUnderSlot({ layout: local.layout, label: "local", args: ["help"], env: { CI: "", PATH: local.path } });
    expect(readFileSync(local.receipt, "utf8")).toContain("ARGS:--offline --no-daemon help");
    const ci = fakeBuildRoot();
    await runUnderSlot({ layout: ci.layout, label: "ci", args: ["help"], env: { CI: "true", PATH: ci.path } });
    expect(readFileSync(ci.receipt, "utf8")).toContain("ARGS:--no-daemon help");
  });

  test("the shell script's flock and this CLI's cannot both hold the slot", async () => {
    const fake = fakeBuildRoot();
    const lock = join(fake.dir, ".gradle-slot.lock");
    writeFileSync(`${lock}.holder`, "another-seat pid=4242 since=2026-09-20T00:00:00-05:00\n");
    // held exactly the way checks/gradle-slot.sh holds it: util-linux flock(1) on fd 9
    const holder = Bun.spawn(["bash", "-c", `exec 9>"${lock}"; flock 9; sleep 30`]);
    try {
      Bun.sleepSync(300);
      const stderr: string[] = [];
      const original = console.error;
      console.error = (...parts: unknown[]) => void stderr.push(parts.join(" "));
      let code: number;
      try {
        code = await runUnderSlot({
          layout: fake.layout,
          label: "blocked",
          args: ["check"],
          env: { CI: "1", GRADLE_SLOT_WAIT_S: "1", PATH: fake.path },
          pollMs: 25,
        });
      } finally {
        console.error = original;
      }
      expect(code).toBe(SLOT_TIMEOUT_EXIT);
      expect(stderr.join("\n")).toContain("gradle-slot: waiting (held by: another-seat pid=4242");
      expect(stderr.join("\n")).toContain("gradle-slot: gave up after 1s (held by: another-seat pid=4242");
      expect(existsSync(fake.receipt)).toBe(false);
    } finally {
      holder.kill();
    }
  });

  test("...and the other direction: while this CLI holds it, the shell's flock cannot", async () => {
    const fake = fakeBuildRoot();
    const lock = join(fake.dir, ".gradle-slot.lock");
    // The probes run from inside gradlew, i.e. while the slot is genuinely held, and use `flock -n`
    // rather than `-w`: a WAITING probe reports success the moment the holder exits, which is how a
    // first attempt at this proof came back green against a lock that was working correctly.
    writeFileSync(
      join(fake.dir, "gradlew"),
      `#!/usr/bin/env bash\nexec 9>"${lock}"\nflock -n 9 && echo GOT >"${fake.receipt}" || echo BUSY >"${fake.receipt}"\nbash -c 'exec 9>"${lock}"; flock -n 9 && echo GOT || echo BUSY' >>"${fake.receipt}"\nexit 0\n`,
    );
    chmodSync(join(fake.dir, "gradlew"), 0o755);
    await runUnderSlot({ layout: fake.layout, label: "holding", args: ["help"], env: { CI: "1", PATH: fake.path } });
    // both probes run from a separate process, the way a second seat's gradle-slot.sh would
    expect(readFileSync(fake.receipt, "utf8").trim().split("\n")).toEqual(["BUSY", "BUSY"]);
  });

  test("the holder timestamp is `date -Is`, to the byte", () => {
    const now = new Date();
    // bun test pins the runtime to UTC; `date` must be asked in the same zone or the two spellings
    // of the same instant differ by an offset and the comparison says nothing about the format.
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const shell = Bun.spawnSync(["date", "-Is", "-d", `@${Math.floor(now.getTime() / 1000)}`], {
      env: { ...process.env, TZ: zone },
    })
      .stdout.toString()
      .trim();
    expect(isoSeconds(now)).toBe(shell);
  });

  test("buildgate wraps gradle when PATH has it, and is skipped when it does not", async () => {
    const fake = fakeBuildRoot();
    const binDir = mkdtempSync(join(tmpdir(), "gate-buildgate-"));
    workspaces.push(binDir);
    const marker = join(binDir, "wrapped.txt");
    writeFileSync(join(binDir, "buildgate"), `#!/usr/bin/env bash\necho WRAPPED >"${marker}"\nexec "$@"\n`);
    chmodSync(join(binDir, "buildgate"), 0o755);

    await runUnderSlot({ layout: fake.layout, label: "wrapped", args: ["help"], env: { CI: "1", PATH: `${binDir}:${fake.path}` } });
    expect(existsSync(marker)).toBe(true);
    expect(readFileSync(fake.receipt, "utf8")).toContain("ARGS:--no-daemon help");

    rmSync(marker);
    const bare = fakeBuildRoot();
    await runUnderSlot({ layout: bare.layout, label: "bare", args: ["help"], env: { CI: "1", PATH: bare.path } });
    expect(existsSync(marker)).toBe(false);
    expect(readFileSync(bare.receipt, "utf8")).toContain("ARGS:--no-daemon help");
  });

  // ── the signal path ───────────────────────────────────────────────────────────────────────────
  //
  // `kill <pid>` on the gate is how a seat, a CI cancel and a supervisor all stop a run, so these
  // signal the wrapper's REAL pid and nothing else — signalling the process group would kill the
  // child too and prove nothing about forwarding. Against the unfixed CLI (Bun.spawnSync, handlers
  // removed in `finally` before a queued signal could run) all three were red: the wrapper ignored
  // SIGTERM, held the slot for the child's whole sleep and exited 0.

  /** `runUnderSlot` in a process of its own, exiting with whatever it returns. */
  function wrapperProcess(fake: ReturnType<typeof fakeBuildRoot>, label: string): Bun.Subprocess {
    const runner = join(fake.dir, "runner.ts");
    const options = { layout: fake.layout, label, args: ["check"], env: { CI: "1", PATH: fake.path } };
    writeFileSync(
      runner,
      `import { runUnderSlot } from ${JSON.stringify(join(import.meta.dir, "..", "src", "lib", "slot.ts"))};\n` +
        `process.exit(await runUnderSlot(${JSON.stringify(options)}));\n`,
    );
    return Bun.spawn([process.execPath, runner], { stdio: ["ignore", "ignore", "ignore"] });
  }

  async function waitForFile(path: string, what: string, ms = 10_000): Promise<void> {
    const deadline = Date.now() + ms;
    while (!existsSync(path)) {
      if (Date.now() > deadline) throw new Error(`the fake gradle never ${what} — no ${path} after ${ms}ms`);
      await Bun.sleep(5);
    }
  }

  for (const [signal, status] of [["SIGTERM", 143], ["SIGINT", 130]] as const) {
    test(`a parent-only ${signal} ends the wrapper with ${status}, long before the child would finish`, async () => {
      // `exec`, so the child is the sleep itself: a bash that only forwards its own death would let
      // the sleep outlive the wrapper and make a prompt exit say nothing about the JVM.
      const fake = fakeBuildRoot('touch "$(dirname "$0")/started"\nexec sleep 5\n');
      const holder = join(fake.dir, ".gradle-slot.lock.holder");
      const wrapper = wrapperProcess(fake, "signalled");
      await waitForFile(join(fake.dir, "started"), "started");
      expect(existsSync(holder)).toBe(true);
      const at = Date.now();
      process.kill(wrapper.pid, signal);
      expect(await wrapper.exited).toBe(status);
      expect(Date.now() - at).toBeLessThan(2_000);
      expect(existsSync(holder)).toBe(false);
    });
  }

  test("the slot and the holder are kept until the child is actually GONE", async () => {
    // The obvious wrong fix — drop the holder and release the lock inside the signal handler, then
    // die — frees the slot while the JVM is still running its shutdown hooks. A contender that
    // starts there is the second gradle in one project dir this file exists to prevent.
    const fake = fakeBuildRoot(
      'trap \'touch "$(dirname "$0")/terminating"; sleep 1; exit 143\' TERM\n' +
        'touch "$(dirname "$0")/started"\nsleep 5 &\nwait $!\n',
    );
    const lock = join(fake.dir, ".gradle-slot.lock");
    const wrapper = wrapperProcess(fake, "shutting-down");
    await waitForFile(join(fake.dir, "started"), "started");
    process.kill(wrapper.pid, "SIGTERM");
    await waitForFile(join(fake.dir, "terminating"), "received the forwarded SIGTERM");

    const contender = takeExclusive(lock, 25, 5);
    contender?.release();
    expect(contender, "a contender took the slot while the child was still shutting down").toBeUndefined();
    expect(existsSync(`${lock}.holder`)).toBe(true);

    expect(await wrapper.exited).toBe(143);
    expect(existsSync(`${lock}.holder`)).toBe(false);
    const free = takeExclusive(lock, 250, 5);
    expect(free, "the slot must be free once the wrapper is gone").toBeDefined();
    free?.release();
  });
});
