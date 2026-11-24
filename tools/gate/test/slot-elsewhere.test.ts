import { afterAll, describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir, type } from "node:os";
import { dirname, join } from "node:path";
import { keptOffThisMachine, noMachineFor } from "../src/lib/elsewhere.ts";
import { runUnderSlot } from "../src/lib/slot.ts";

const folders: string[] = [];
afterAll(() => {
  for (const dir of folders) rmSync(dir, { recursive: true, force: true });
});

const jar = "app/build/libs/app-all.jar";
const bytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0xff, 0x00]);
const matching = type() === "Darwin" ? "on-mac" : "on-box";
const other = matching === "on-box" ? "on-mac" : "on-box";

/** Real slot and remote command, with only the transport and the Gradle workload replaced. */
function worktree(runners = [matching, other]) {
  const dir = mkdtempSync(join(tmpdir(), "gate-elsewhere-test-"));
  folders.push(dir);
  const bin = join(dir, "bin");
  const remote = join(dir, "remote");
  const gate = "tools/gate/index.ts";
  const receipt = join(dir, "built-here");
  mkdirSync(bin);
  symlinkSync(process.execPath, join(bin, "bun"));
  writeFileSync(join(bin, "rig"), "#!/bin/sh\nexit 64\n", { mode: 0o755 });
  writeFileSync(join(dir, "gradlew"), `#!/bin/sh\nprintf '%s\\n' "$@" > "${receipt}"\n`, { mode: 0o755 });
  for (const root of [dir, remote]) mkdirSync(join(root, dirname(gate)), { recursive: true });
  writeFileSync(join(dir, gate), "throw new Error('this fixture must check off-host');");
  writeFileSync(join(remote, gate), `
    if (Bun.env.SPLICE_GRADLE_HERE !== "1" || process.argv[2] !== "slot" || process.argv[3] !== "elsewhere-test") process.exit(66);
    if (process.argv.includes(":app:test") && !process.argv.includes("splice.Test with spaces")) process.exit(67);
    if (process.argv.includes(":app:shadowJar")) {
      if (!process.argv.includes("-Psplice.allPlatforms=" + (Bun.env.PLATFORMS ?? "true"))) process.exit(68);
      if (!Bun.env.NO_JAR) await Bun.write(${JSON.stringify(jar)}, new Uint8Array(${JSON.stringify([...bytes])}));
      console.log("build logs, not jar bytes");
    }
    process.exit(Number(Bun.env.GRADLE_STATUS ?? "0"));
  `);
  const wrong = join(dir, "wrong-system");
  mkdirSync(wrong);
  writeFileSync(join(wrong, "uname"), "#!/bin/sh\necho Wrong wrong\n", { mode: 0o755 });
  for (const name of runners) {
    writeFileSync(join(bin, name), [
      "#!/bin/sh",
      'if [ -n "${RUNNER_STATUS:-}" ]; then exit "$RUNNER_STATUS"; fi',
      `if [ -n "\${WRONG_SYSTEM:-}" ]; then PATH="${wrong}:$PATH"; export PATH; fi`,
      `cd "${remote}" || exit`,
      'exec "$@"',
    ].join("\n"), { mode: 0o755 });
  }
  const location = (env: Record<string, string | undefined> = {}) => ({
    buildRoot: dir,
    env: {
      PATH: `${bin}:/usr/bin:/bin`,
      CI: undefined,
      SPLICE_GRADLE_HERE: undefined,
      GRADLE_SLOT_LOCK: join(dir, ".gradle-slot.lock"),
      ...env,
    },
  });
  return {
    location,
    run: (args: string[], env?: Record<string, string | undefined>) =>
      runUnderSlot({ layout: { repoRoot: dir, buildRoot: dir }, label: "elsewhere-test", args, env: location(env).env }),
    builtHere: () => existsSync(receipt),
    localJar: () => new Uint8Array(readFileSync(join(dir, jar))),
  };
}

const checks = [":app:test", "--tests", "splice.Test with spaces"];

describe("Gradle on another machine", () => {
  test("passes tasks and arguments to the matching runner and returns the check's own status", async () => {
    for (const status of [0, 3, 75]) {
      const tree = worktree([matching]);
      expect(await tree.run(checks, { GRADLE_STATUS: String(status) })).toBe(status);
      expect(tree.builtHere()).toBe(false);
    }
  });

  test("does not run a check on a different platform, even if the runner answers", async () => {
    const tree = worktree([matching]);
    expect(await tree.run(checks, { WRONG_SYSTEM: "1" })).toBe(75);
    expect(tree.builtHere()).toBe(false);
    expect(noMachineFor(tree.location())).toBeUndefined();
  });

  test("only an absent platform runner leaves the tier unrun; a transport failure is not a pass", async () => {
    const missing = worktree([other]);
    expect(noMachineFor(missing.location())).toContain(`no ${matching} on PATH`);
    expect(await missing.run(checks)).toBe(75);
    expect(missing.builtHere()).toBe(false);

    const installed = worktree([matching]);
    expect(noMachineFor(installed.location({ RUNNER_STATUS: "75" }))).toBeUndefined();
    for (const status of [64, 75, 255]) expect(await installed.run(checks, { RUNNER_STATUS: String(status) })).toBe(status);
    expect(installed.builtHere()).toBe(false);
  });

  test("a portable jar can return from the other platform, with no build logs in its bytes", async () => {
    const tree = worktree([other]);
    expect(await tree.run([":app:shadowJar"], { WRONG_SYSTEM: "1" })).toBe(0);
    expect(tree.localJar()).toEqual(bytes);
    expect(tree.builtHere()).toBe(false);
  });

  test("an explicitly nonportable jar needs a matching platform and keeps the requested setting", async () => {
    const tree = worktree([matching]);
    expect(await tree.run([":app:shadowJar", "-Psplice.allPlatforms=false"], { PLATFORMS: "false" })).toBe(0);
    expect(tree.localJar()).toEqual(bytes);
    expect(await tree.run([":app:shadowJar", "-Psplice.allPlatforms=false"], { PLATFORMS: "false", WRONG_SYSTEM: "1" })).toBe(75);
  });

  test("a missing or failed remote artifact does not publish a replacement jar", async () => {
    const tree = worktree([matching]);
    expect(await tree.run([":app:shadowJar"], { NO_JAR: "1" })).not.toBe(0);
    expect(() => tree.localJar()).toThrow();
    expect(await tree.run([":app:shadowJar"])).toBe(0);
    expect(await tree.run([":app:shadowJar"], { GRADLE_STATUS: "3" })).toBe(3);
    expect(tree.localJar()).toEqual(bytes);
    expect(tree.builtHere()).toBe(false);
  });

  test("paths and outputs that cannot travel require an explicit local run", async () => {
    const tree = worktree();
    for (const args of [[":app:installDist"], ["-p", "/tmp/project", "test"], ["--init-script=/tmp/ci.gradle", "test"]]) {
      expect(await tree.run(args)).toBe(75);
      expect(tree.builtHere()).toBe(false);
    }
  });

  test("CI, an explicit local run and a machine without either runner build in place", async () => {
    for (const env of [{ CI: "true" }, { SPLICE_GRADLE_HERE: "1" }, { PATH: "/usr/bin:/bin" }]) {
      const tree = worktree();
      expect(keptOffThisMachine(tree.location(env))).toBe(false);
      expect(noMachineFor(tree.location(env))).toBeUndefined();
      expect(await tree.run(checks, env)).toBe(0);
      expect(tree.builtHere()).toBe(true);
    }
  });
});
