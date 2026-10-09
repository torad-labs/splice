// The slot's contract, and the one property that makes the port safe to land beside the shell
// script it ports: both take flock(2) on the SAME path, so they can never both hold the slot.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { isoSeconds, lockPath, NO_TASKS_EXIT, runUnderSlot, SLOT_TIMEOUT_EXIT } from "../src/lib/slot.ts";
import { takeExclusive } from "../src/lib/flock.ts";
import { layout } from "../src/lib/repo.ts";

const real = layout();
const workspaces: string[] = [];
// The suite runs on its own locks. runUnderSlot lays the process environment under its options (#170),
// so a seat that exports GRADLE_SLOT_LOCK (the V4-341 workaround for worktrees at older shas) sent six
// of these runs to that lock instead of their own, red, and holding the repository's real slot.
const inherited = { lock: process.env.GRADLE_SLOT_LOCK, wait: process.env.GRADLE_SLOT_WAIT_S };
delete process.env.GRADLE_SLOT_LOCK;
delete process.env.GRADLE_SLOT_WAIT_S;
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
  if (inherited.lock !== undefined) process.env.GRADLE_SLOT_LOCK = inherited.lock;
  if (inherited.wait !== undefined) process.env.GRADLE_SLOT_WAIT_S = inherited.wait;
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
  // The shared checkout is the git common dir's parent. Read from there, not from where this suite runs: the gate and
  // pre-push run it inside their own build trees, which take their own locks (the next tests).
  test("the lock is the repository's: in its git common dir, where nothing is ever tracked (V4-341)", () => {
    const common = git(real.repoRoot, "rev-parse", "--path-format=absolute", "--git-common-dir").trim();
    const checkout = { repoRoot: dirname(common), buildRoot: dirname(common) };
    expect(lockPath(checkout, {})).toBe(join(common, "gradle-slot.lock"));
  });

  test("with no repository at the root, the lock falls back to the path .gitignore keeps out of the tree", () => {
    // checks/gradle-slot.sh used to be the oracle; since PR 5 the external record of the lock's
    // name is the ignore line — a lock the CLI wrote under any other name would be committed.
    const ignore = readFileSync(join(real.repoRoot, ".gitignore"), "utf8");
    const line = /^\/(\.gradle-slot\.lock)\*$/m.exec(ignore);
    expect(line, ".gitignore must still name the slot lock the way this test reads it").not.toBeNull();
    // and the fallback derives it from the build root rather than the literal `gateway`, so it
    // follows the build root when the restructure moves it to the repository root
    const fake = fakeBuildRoot();
    expect(lockPath(fake.layout, {})).toBe(join(fake.layout.buildRoot, line![1]!));
  });

  test("each build tree takes its own lock, so neither a push nor a local gate blocks the checkout's builders", () => {
    const common = git(real.repoRoot, "rev-parse", "--path-format=absolute", "--git-common-dir").trim();
    const checkout = { repoRoot: dirname(common), buildRoot: dirname(common) };
    for (const name of ["tree", "gate"]) {
      const tree = join(common, "splice-prepush", name);
      const layout = { repoRoot: tree, buildRoot: tree };
      expect(lockPath(layout, {})).toBe(join(tree, ".gradle-slot.lock"));
      expect(lockPath(layout, {})).not.toBe(lockPath(checkout, {}));
    }
  });

  test("GRADLE_SLOT_LOCK overrides it, as in the script", () => {
    expect(lockPath(real, { GRADLE_SLOT_LOCK: "/tmp/elsewhere.lock" })).toBe("/tmp/elsewhere.lock");
  });

  // #170 review: `gate run` passes only JAVA_HOME as overrides, and the first cut read the lock
  // settings from THAT map — the operator's GRADLE_SLOT_LOCK and GRADLE_SLOT_WAIT_S in the process
  // environment were discarded, so the gate took the worktree's default lock beside a competing
  // build. Overrides lie over the environment; they never replace it.
  test("the environment's lock settings survive a caller that passes only overrides", async () => {
    const fake = fakeBuildRoot('echo "ARGS:$*"\nexit 0\n');
    mkdirSync(join(fake.dir, "elsewhere"));
    const elsewhere = join(fake.dir, "elsewhere", "shared.lock");
    const before = { lock: process.env.GRADLE_SLOT_LOCK, wait: process.env.GRADLE_SLOT_WAIT_S };
    process.env.GRADLE_SLOT_LOCK = elsewhere;
    process.env.GRADLE_SLOT_WAIT_S = "1";
    try {
      const code = await runUnderSlot({ layout: fake.layout, label: "overrides-only", args: ["help"], env: { CI: "1", PATH: fake.path, JAVA_HOME: "/nonexistent-jdk" } });
      expect(code).toBe(0);
      expect(existsSync(elsewhere), "the slot must lock GRADLE_SLOT_LOCK from the environment").toBe(true);
      expect(existsSync(join(fake.dir, ".gradle-slot.lock")), "and never the worktree default beside it").toBe(false);
      // and the wait comes from the environment too: a held lock gives up after 1s, not an hour
      const held = takeExclusive(elsewhere, 1000, 50)!;
      try {
        const started = Date.now();
        expect(await runUnderSlot({ layout: fake.layout, label: "busy", args: ["help"], env: { CI: "1", PATH: fake.path } })).toBe(SLOT_TIMEOUT_EXIT);
        expect(Date.now() - started).toBeLessThan(10_000);
      } finally {
        held.release();
      }
    } finally {
      if (before.lock === undefined) delete process.env.GRADLE_SLOT_LOCK; else process.env.GRADLE_SLOT_LOCK = before.lock;
      if (before.wait === undefined) delete process.env.GRADLE_SLOT_WAIT_S; else process.env.GRADLE_SLOT_WAIT_S = before.wait;
    }
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
    expect(receipt).toContain("ARGS:--parallel --no-daemon :app:test");
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
    expect(readFileSync(local.receipt, "utf8")).toContain("ARGS:--offline ");
    const ci = fakeBuildRoot();
    await runUnderSlot({ layout: ci.layout, label: "ci", args: ["help"], env: { CI: "true", PATH: ci.path } });
    expect(readFileSync(ci.receipt, "utf8")).not.toContain("--offline");
  });

  test("a local run names the warm daemon's caps on the command line, where nothing in ~/.gradle outranks them", async () => {
    const local = fakeBuildRoot();
    await runUnderSlot({ layout: local.layout, label: "local", args: ["help"], env: { CI: "", PATH: local.path } });
    const receipt = readFileSync(local.receipt, "utf8");
    expect(receipt).toContain("-Dorg.gradle.jvmargs=-Xmx1536m");
    expect(receipt).toContain("-Dorg.gradle.daemon.idletimeout=1800000");
  });

  test("--parallel is CI's alone: on this box one project builds at a time", async () => {
    const local = fakeBuildRoot();
    await runUnderSlot({ layout: local.layout, label: "local", args: ["help"], env: { CI: "", PATH: local.path } });
    expect(readFileSync(local.receipt, "utf8")).toContain("ARGS:--offline ");
    const ci = fakeBuildRoot();
    await runUnderSlot({ layout: ci.layout, label: "ci", args: ["help"], env: { CI: "true", PATH: ci.path } });
    expect(readFileSync(ci.receipt, "utf8")).toContain("ARGS:--parallel --no-daemon help");
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

  /** The host owns a close-on-exec flock and spawns its command; memory-held builds own no lock. */
  function admissionGate(
    fake: ReturnType<typeof fakeBuildRoot>,
    capable: boolean,
    probe: { readonly usage?: string; readonly exit?: number; readonly stream?: "stdout" | "stderr" } = {},
  ) {
    const bin = join(fake.dir, "bin");
    const binary = join(bin, "buildgate");
    mkdirSync(bin);
    symlinkSync(process.execPath, join(bin, "bun"));
    const usage = probe.usage ?? `usage: buildgate [--nogate] [--exclusive] ${capable ? "[--joint] " : ""}CMD ARGS...\n`;
    writeFileSync(binary, `#!${process.execPath}
import { existsSync, writeFileSync } from "node:fs";
import { takeExclusive } from ${JSON.stringify(join(real.repoRoot, "tools/gate/src/lib/flock.ts"))};
const root = ${JSON.stringify(fake.dir)};
const args = process.argv.slice(2);
if (args.length === 0) {
  process.${probe.stream ?? "stderr"}.write(${JSON.stringify(usage)});
  process.exit(${probe.exit ?? 2});
}
const run = process.env.SLOT_FAKE_RUN;
writeFileSync(root + "/argv-" + run, JSON.stringify(args));
writeFileSync(root + "/lock-" + run, process.env.BUILDGATE_LOCK ?? "unset");
writeFileSync(root + "/entered-" + run, "");
let child;
let signal;
for (const name of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(name, () => { signal = name; child?.kill(name); });
}
if (run === "held") {
  while (!existsSync(root + "/release-memory") && !signal) await Bun.sleep(10);
}
let slot;
if (args[0] === "--exclusive" && args[1] === "--joint") {
  args.splice(0, 2);
  slot = takeExclusive(process.env.BUILDGATE_LOCK, 0, 1);
  if (!slot) writeFileSync(root + "/waiting-lock-" + run, "");
  while (!slot && !signal) {
    await Bun.sleep(10);
    slot = takeExclusive(process.env.BUILDGATE_LOCK, 0, 1);
  }
}
try {
  if (signal) process.exit(143);
  child = Bun.spawn(args, { env: process.env, stdio: ["inherit", "inherit", "inherit"] });
  const code = await child.exited;
  process.exitCode = code;
} finally {
  slot?.release();
}
`);
    chmodSync(binary, 0o755);
    return { path: `${bin}:${fake.path}`, binary };
  }

  test("a joint-capable buildgate lets an admitted run pass a memory-held run (V4-426)", async () => {
    const fake = fakeBuildRoot('printf "%s\\n" "$SLOT_FAKE_RUN"\nexit 0\n');
    const gate = admissionGate(fake, true);
    const lock = join(fake.dir, ".gradle-slot.lock");
    const env = { CI: "1", PATH: gate.path, GRADLE_SLOT_WAIT_S: "0", BUILDGATE_LOCK: undefined };
    const first = runUnderSlot({ layout: fake.layout, label: "held-memory", args: ["help"],
      env: { ...env, SLOT_FAKE_RUN: "held" }, pollMs: 5 });
    let code = -1;
    let holderWhileHeld = false;
    try {
      await waitForFile(join(fake.dir, "entered-held"), "entered buildgate before memory admission");
      holderWhileHeld = existsSync(`${lock}.holder`);
      code = await runUnderSlot({ layout: fake.layout, label: "admitted", args: ["help"],
        env: { ...env, SLOT_FAKE_RUN: "admitted" }, pollMs: 5 });
    } finally {
      writeFileSync(join(fake.dir, "release-memory"), "");
      expect(await first).toBe(0);
    }
    expect(code, "a memory-held build must not take the caller's pre-flock").toBe(0);
    expect(holderWhileHeld, "the held build is not a running gradle holder").toBe(false);
    expect(JSON.parse(readFileSync(join(fake.dir, "argv-admitted"), "utf8")))
      .toEqual(["--exclusive", "--joint", join(real.repoRoot, "tools/gate/bin/gradlew"), "--parallel", "--no-daemon", "help"]);
    expect(readFileSync(join(fake.dir, "lock-admitted"), "utf8").trim()).toBe(lock);
  }, 10_000);

  test("jointly admitted builds still exclude each other until gradle exits (V4-426)", async () => {
    const fake = fakeBuildRoot(
      'touch "$(dirname "$0")/started-$SLOT_FAKE_RUN"\n' +
      'if [[ "$SLOT_FAKE_RUN" == first ]]; then while [[ ! -e "$(dirname "$0")/release-gradle" ]]; do sleep 0.01; done; fi\nexit 0\n',
    );
    const gate = admissionGate(fake, true);
    const env = { CI: "1", PATH: gate.path, GRADLE_SLOT_WAIT_S: "0", BUILDGATE_LOCK: undefined };
    const first = runUnderSlot({ layout: fake.layout, label: "first-admitted", args: ["help"],
      env: { ...env, SLOT_FAKE_RUN: "first" } });
    let second: Promise<number> | undefined;
    try {
      await waitForFile(join(fake.dir, "started-first"), "started the first admitted gradle");
      second = runUnderSlot({ layout: fake.layout, label: "second-admitted", args: ["help"],
        env: { ...env, SLOT_FAKE_RUN: "second" } });
      await waitForFile(join(fake.dir, "waiting-lock-second"), "waited inside buildgate on the joint lock");
      expect(existsSync(join(fake.dir, "started-second")), "only one admitted gradle may run").toBe(false);
    } finally {
      writeFileSync(join(fake.dir, "release-gradle"), "");
      expect(await first).toBe(0);
      if (second) expect(await second).toBe(0);
    }
    expect(existsSync(join(fake.dir, "started-second"))).toBe(true);
  }, 15_000);

  for (const mode of ["unsupported", "absent", "non-token", "failed-probe", "stdout-only"] as const) {
    test(`${mode} buildgate preserves today's argv, holder and pre-flock (V4-426)`, async () => {
      const fake = fakeBuildRoot('printf "ARGS:%s\\n" "$*"\n' +
        'flock -n "$LOCK" true && printf "FREE\\n" || printf "BUSY\\n"\n' +
        'cat "$LOCK.holder"\nexit 0\n');
      const probe = mode === "non-token" ? { usage: "usage: buildgate [--exclusive] [--jointly] CMD ARGS...\n" }
        : mode === "failed-probe" ? { exit: 0 }
        : mode === "stdout-only" ? { stream: "stdout" as const } : {};
      const path = mode === "absent" ? fake.path : admissionGate(fake, mode !== "unsupported", probe).path;
      const code = await runUnderSlot({ layout: fake.layout, label: "legacy", args: ["help"],
        env: { CI: "1", PATH: path, SLOT_FAKE_RUN: "legacy", BUILDGATE_LOCK: undefined } });
      expect(code).toBe(0);
      const receipt = readFileSync(fake.receipt, "utf8");
      expect(receipt).toContain("ARGS:--parallel --no-daemon help\nBUSY\nlegacy pid=");
      expect(existsSync(join(fake.dir, ".gradle-slot.lock.holder"))).toBe(false);
      if (mode !== "absent") {
        expect(JSON.parse(readFileSync(join(fake.dir, "argv-legacy"), "utf8")))
          .toEqual([join(fake.dir, "gradlew"), "--parallel", "--no-daemon", "help"]);
        expect(readFileSync(join(fake.dir, "lock-legacy"), "utf8").trim()).toBe("unset");
      }
    });
  }

  test("a cancelled joint memory waiter never writes or deletes another build's holder (V4-426)", async () => {
    const fake = fakeBuildRoot();
    const gate = admissionGate(fake, true);
    const lock = join(fake.dir, ".gradle-slot.lock");
    const held = takeExclusive(lock, 1000, 5);
    expect(held).not.toBeNull();
    const holder = `${lock}.holder`;
    const owner = "synthetic-running pid=4242 since=2026-10-05T12:00:00-05:00\n";
    writeFileSync(holder, owner);
    const runner = wrapperProcess(fake, "held-memory", { PATH: gate.path, SLOT_FAKE_RUN: "held" });
    try {
      await waitForFile(join(fake.dir, "entered-held"), "entered memory admission");
      expect(readFileSync(holder, "utf8")).toBe(owner);
      runner.kill("SIGTERM");
      expect(await runner.exited).toBe(143);
      expect(readFileSync(holder, "utf8")).toBe(owner);
      expect(existsSync(fake.receipt)).toBe(false);
    } finally {
      writeFileSync(join(fake.dir, "release-memory"), "");
      runner.kill();
      held?.release();
      await runner.exited;
    }
  }, 15_000);

  test("a signalled joint build keeps its host lock until Gradle actually exits (V4-426)", async () => {
    const fake = fakeBuildRoot(
      'trap \'touch "$(dirname "$0")/stopping"; while [[ ! -e "$(dirname "$0")/release-stop" ]]; do sleep 0.01; done; exit 143\' TERM\n' +
      'touch "$(dirname "$0")/started"\nwhile true; do sleep 0.01; done\n',
    );
    const gate = admissionGate(fake, true);
    const lock = join(fake.dir, ".gradle-slot.lock");
    const runner = wrapperProcess(fake, "stopping-build", { PATH: gate.path, SLOT_FAKE_RUN: "active" });
    try {
      await waitForFile(join(fake.dir, "started"), "started the admitted gradle");
      runner.kill("SIGTERM");
      await waitForFile(join(fake.dir, "stopping"), "forwarded the signal through buildgate");
      expect(readFileSync(`${lock}.holder`, "utf8")).toContain("stopping-build pid=");
      const premature = takeExclusive(lock, 50, 5);
      premature?.release();
      expect(premature, "the host must hold the slot while its child handles shutdown").toBeUndefined();
      writeFileSync(join(fake.dir, "release-stop"), "");
      expect(await runner.exited).toBe(143);
      const free = takeExclusive(lock, 50, 5);
      expect(free).toBeDefined();
      free?.release();
    } finally {
      writeFileSync(join(fake.dir, "release-stop"), "");
      runner.kill();
      await runner.exited;
    }
  }, 15_000);

  test("the host keeps its lock while the gradlew-named wrapper preserves every argument (V4-426)", async () => {
    const fake = fakeBuildRoot(
      'if flock -n "$LOCK" true; then exit 9; fi\n' +
      'printf "%s\\0" "$@" >"$(dirname "$0")/real-argv"\ncat "$LOCK.holder"\n',
    );
    const gate = admissionGate(fake, true);
    const args = ["help", "-Psynthetic=line one\nline two", ""];
    expect(await runUnderSlot({ layout: fake.layout, label: "admitted", args,
      env: { CI: "", PATH: gate.path, SLOT_FAKE_RUN: "admitted" } })).toBe(0);
    const hostArgv: string[] = JSON.parse(readFileSync(join(fake.dir, "argv-admitted"), "utf8"));
    expect(hostArgv.slice(0, 3)).toEqual(["--exclusive", "--joint", join(real.repoRoot, "tools/gate/bin/gradlew")]);
    expect(hostArgv.slice(-args.length)).toEqual(args);
    const wrapped = readFileSync(join(fake.dir, "real-argv"), "utf8").split("\0").slice(0, -1);
    expect(wrapped[0]).toBe("--offline");
    expect(wrapped.slice(-args.length)).toEqual(args);
    expect(readFileSync(fake.receipt, "utf8")).toMatch(/^admitted pid=\d+ since=/);
  });

  test("a surviving Gradle daemon inherits no host slot descriptor (V4-426)", async () => {
    const fake = fakeBuildRoot(
      'sleep 30 >/dev/null 2>&1 &\nprintf "%s" "$!" >"$(dirname "$0")/daemon-pid"\nexit 0\n',
    );
    const gate = admissionGate(fake, true);
    const pidFile = join(fake.dir, "daemon-pid");
    const env = { CI: "1", PATH: gate.path, SLOT_FAKE_RUN: "daemon", GRADLE_SLOT_WAIT_S: "0" };
    try {
      expect(await runUnderSlot({ layout: fake.layout, label: "daemon-build", args: ["help"], env })).toBe(0);
      const pid = Number(readFileSync(pidFile, "utf8"));
      expect(() => process.kill(pid, 0)).not.toThrow();
      const free = takeExclusive(join(fake.dir, ".gradle-slot.lock"), 50, 5);
      expect(free, "a daemon outliving the build cannot retain its host's slot").not.toBeNull();
      free?.release();
      expect(await runUnderSlot({ layout: fake.layout, label: "next-build", args: ["help"],
        env: { ...env, SLOT_FAKE_RUN: "next" } })).toBe(0);
    } finally {
      // Only the descendant this synthetic fixture created is stopped.
      if (existsSync(pidFile)) process.kill(Number(readFileSync(pidFile, "utf8")), "SIGTERM");
    }
  }, 15_000);

  test("buildgate wraps gradle when PATH has it, and is skipped when it does not", async () => {
    const fake = fakeBuildRoot();
    const binDir = mkdtempSync(join(tmpdir(), "gate-buildgate-"));
    workspaces.push(binDir);
    const marker = join(binDir, "wrapped.txt");
    writeFileSync(join(binDir, "buildgate"), `#!/usr/bin/env bash\necho WRAPPED >"${marker}"\nexec "$@"\n`);
    chmodSync(join(binDir, "buildgate"), 0o755);

    await runUnderSlot({ layout: fake.layout, label: "wrapped", args: ["help"], env: { CI: "1", PATH: `${binDir}:${fake.path}` } });
    expect(existsSync(marker)).toBe(true);
    expect(readFileSync(fake.receipt, "utf8")).toContain("ARGS:--parallel --no-daemon help");

    rmSync(marker);
    const bare = fakeBuildRoot();
    await runUnderSlot({ layout: bare.layout, label: "bare", args: ["help"], env: { CI: "1", PATH: bare.path } });
    expect(existsSync(marker)).toBe(false);
    expect(readFileSync(bare.receipt, "utf8")).toContain("ARGS:--parallel --no-daemon help");
  });

  // ── the signal path ───────────────────────────────────────────────────────────────────────────
  //
  // `kill <pid>` on the gate is how a seat, a CI cancel and a supervisor all stop a run, so these
  // signal the wrapper's REAL pid and nothing else — signalling the process group would kill the
  // child too and prove nothing about forwarding. Against the unfixed CLI (Bun.spawnSync, handlers
  // removed in `finally` before a queued signal could run) all three were red: the wrapper ignored
  // SIGTERM, held the slot for the child's whole sleep and exited 0.

  /** `runUnderSlot` in a process of its own, exiting with whatever it returns. */
  function wrapperProcess(
    fake: ReturnType<typeof fakeBuildRoot>,
    label: string,
    env: Record<string, string | undefined> = {},
  ): Bun.Subprocess {
    const runner = join(fake.dir, "runner.ts");
    const options = { layout: fake.layout, label, args: ["check"], env: { CI: "1", PATH: fake.path, ...env } };
    writeFileSync(
      runner,
      `import { runUnderSlot } from ${JSON.stringify(join(import.meta.dir, "..", "src", "lib", "slot.ts"))};\n` +
        `process.exit(await runUnderSlot(${JSON.stringify(options)}));\n`,
    );
    // process.env passed explicitly: the spawn's default is the environment the suite started with,
    // GRADLE_SLOT_LOCK included, not the one it cleared above
    return Bun.spawn([process.execPath, runner], { stdio: ["ignore", "ignore", "ignore"], env: { ...process.env } });
  }

  async function waitForFile(path: string, what: string, ms = 10_000): Promise<void> {
    const deadline = Date.now() + ms;
    while (!existsSync(path)) {
      if (Date.now() > deadline) throw new Error(`the fake gradle never ${what} — no ${path} after ${ms}ms`);
      await Bun.sleep(5);
    }
  }

  /** Until the fake's pid (written by the script before its `exec`) IS the sleep. A marker file alone
   *  races the exec: a SIGINT that lands while bash still waits on the command that wrote the marker
   *  is swallowed by bash's wait-and-cooperative-exit (the child exited normally, so bash carries on)
   *  and the exec'd sleep runs its full 5 s. SIGTERM has no such rule, which is why only the SIGINT
   *  arm lost CI run 36051292857 (5000 ms) while its twin took 38 ms. */
  async function waitForExec(pidFile: string, comm: string, ms = 10_000): Promise<void> {
    const deadline = Date.now() + ms;
    for (;;) {
      const pid = existsSync(pidFile) ? Number.parseInt(readFileSync(pidFile, "utf8"), 10) : Number.NaN;
      if (Number.isInteger(pid)) {
        const ps = Bun.spawnSync(["ps", "-o", "comm=", "-p", String(pid)], { stdout: "pipe", stderr: "ignore" });
        if (ps.stdout.toString().trim() === comm) return;
      }
      if (Date.now() > deadline) throw new Error(`the fake gradle never became ${comm} — ${pidFile} after ${ms}ms`);
      await Bun.sleep(5);
    }
  }

  for (const [signal, status] of [["SIGTERM", 143], ["SIGINT", 130]] as const) {
    test(`a parent-only ${signal} ends the wrapper with ${status}, long before the child would finish`, async () => {
      // `exec`, so the child is the sleep itself: a bash that only forwards its own death would let
      // the sleep outlive the wrapper and make a prompt exit say nothing about the JVM.
      const fake = fakeBuildRoot('echo $$ >"$(dirname "$0")/started"\nexec sleep 5\n');
      const holder = join(fake.dir, ".gradle-slot.lock.holder");
      const wrapper = wrapperProcess(fake, "signalled");
      await waitForExec(join(fake.dir, "started"), "sleep");
      expect(existsSync(holder)).toBe(true);
      const at = Date.now();
      process.kill(wrapper.pid, signal);
      expect(await wrapper.exited).toBe(status);
      expect(Date.now() - at).toBeLessThan(2_000);
      expect(existsSync(holder)).toBe(false);
    });
  }

  // ── one slot per repository (V4-341) ──────────────────────────────────────────────────────────
  //
  // The lock used to sit in each worktree's build root, so a seat's `git worktree add --detach` build
  // never queued behind the checkout's, or behind another seat's worktree: on 2026-09-26 two seats'
  // worktrees both "held the slot" at once and the contention pushed HeadServerLoadTest past its 30 s
  // cap.

  /** A scratch repository and a detached worktree of it: two layouts on one git common dir. Each root's
   *  fake gradle writes its pid and then becomes a 5 s sleep, so a run holds the slot while it sleeps. */
  function worktreePair() {
    const base = mkdtempSync(join(tmpdir(), "gate-slot-pair-"));
    workspaces.push(base);
    const checkout = join(base, "checkout");
    const worktree = join(base, "worktree");
    mkdirSync(checkout);
    git(checkout, "init", "-q");
    git(checkout, "-c", "user.name=gate", "-c", "user.email=gate@test", "-c", "commit.gpgsign=false",
      "-c", "core.hooksPath=/dev/null", "commit", "-q", "--allow-empty", "-m", "base");
    git(checkout, "worktree", "add", "-q", "--detach", worktree);
    const root = (dir: string) => {
      writeFileSync(join(dir, "gradlew"), '#!/usr/bin/env bash\necho $$ >"$(dirname "$0")/started"\nexec sleep 5\n');
      chmodSync(join(dir, "gradlew"), 0o755);
      return { layout: { repoRoot: dir, buildRoot: dir }, dir, receipt: join(dir, "receipt.txt"), path: "/usr/bin:/bin" };
    };
    return { checkout: root(checkout), worktree: root(worktree), common: realpathSync(join(checkout, ".git")) };
  }

  /** git without the caller's GIT_* variables: a suite run from a hook must never touch the real repository. */
  function git(cwd: string, ...args: string[]): string {
    const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith("GIT_")));
    const run = Bun.spawnSync(["git", ...args], { cwd, env, stdout: "pipe", stderr: "pipe" });
    if (run.exitCode !== 0) throw new Error(`git ${args.join(" ")} in ${cwd}: ${run.stderr.toString()}`);
    return run.stdout.toString();
  }

  test("the checkout and its worktree resolve one lock, and GRADLE_SLOT_LOCK still overrides it", () => {
    const pair = worktreePair();
    const shared = join(pair.common, "gradle-slot.lock");
    expect(lockPath(pair.checkout.layout, {})).toBe(shared);
    expect(lockPath(pair.worktree.layout, {})).toBe(shared);
    expect(lockPath(pair.worktree.layout, { GRADLE_SLOT_LOCK: "/tmp/elsewhere.lock" })).toBe("/tmp/elsewhere.lock");
  });

  test("the lock is resolved from the layout's own root: not a repository above it, not the caller's GIT_DIR", () => {
    const pair = worktreePair();
    const nested = join(pair.checkout.dir, "nested");
    mkdirSync(nested);
    expect(lockPath({ repoRoot: nested, buildRoot: nested }, {})).toBe(join(nested, ".gradle-slot.lock"));
    const outside = fakeBuildRoot();
    const before = process.env.GIT_DIR;
    process.env.GIT_DIR = join(pair.checkout.dir, ".git");
    try {
      expect(lockPath(outside.layout, {})).toBe(join(outside.dir, ".gradle-slot.lock"));
    } finally {
      if (before === undefined) delete process.env.GIT_DIR; else process.env.GIT_DIR = before;
    }
  });

  test("a checkout and a worktree of it share one slot: the second run waits on the first", async () => {
    const pair = worktreePair();
    const first = wrapperProcess(pair.checkout, "checkout-run");
    try {
      await waitForExec(join(pair.checkout.dir, "started"), "sleep");
      const stderr: string[] = [];
      const original = console.error;
      console.error = (...parts: unknown[]) => void stderr.push(parts.join(" "));
      let code: number;
      try {
        code = await runUnderSlot({
          layout: pair.worktree.layout,
          label: "worktree-run",
          args: ["check"],
          env: { CI: "1", PATH: pair.worktree.path, GRADLE_SLOT_WAIT_S: "1" },
          pollMs: 25,
        });
      } finally {
        console.error = original;
      }
      expect(code, "the worktree's run must queue behind the checkout's, not beside it").toBe(SLOT_TIMEOUT_EXIT);
      expect(stderr.join("\n")).toContain("gradle-slot: waiting (held by: checkout-run pid=");
      expect(existsSync(join(pair.worktree.dir, "started")), "the worktree's gradle never started").toBe(false);
    } finally {
      first.kill();
      await first.exited;
    }
  }, 20_000);

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
