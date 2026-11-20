// signin-e2e local-install: the maintainer's local install (a release built and installed on one machine without a
// version bump) is one command, `bun tools/release local [--jar PATH]`, that runs the sign-in check on the jar before it
// installs it with install.sh, and a failing sign-in check refuses the install by name: nothing is installed. The jar
// here is made up and `java` on PATH runs no daemon, so the sign-in check cannot pass on it; the install folders are
// a throwaway HOME's. It drives the real sign-in check, so it runs on a Mac (`rig remote run --needs
// system=Darwin/arm64 -- bun test <this file>`).

import { afterAll, expect, setDefaultTimeout, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ROOT } from "./lib/report.ts";

setDefaultTimeout(600_000);

const dirs: string[] = [];
afterAll(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
});
const scratch = (prefix: string) => {
  const d = mkdtempSync(join(tmpdir(), prefix));
  dirs.push(d);
  return d;
};

/** `java` that runs no daemon: whatever it is asked, it says so and exits 1 at once. */
function fakeJava(): string {
  const dir = scratch("signin-e2e-local-java-");
  writeFileSync(join(dir, "java"), '#!/bin/sh\necho "made-up java: no daemon in this jar" >&2\nexit 1\n');
  chmodSync(join(dir, "java"), 0o755);
  return dir;
}

test("a local install whose sign-in check fails installs nothing and says why", () => {
  const home = scratch("signin-e2e-local-home-");
  const jar = join(scratch("signin-e2e-local-jar-"), "app-all.jar");
  writeFileSync(jar, "made-up jar\n");
  const share = join(home, ".local/share/splice");
  const bin = join(home, ".local/bin");

  const r = spawnSync("bun", ["tools/release", "local", "--jar", jar], {
    cwd: ROOT,
    encoding: "utf8",
    env: {
      ...process.env,
      HOME: home,
      PATH: `${fakeJava()}:${process.env.PATH ?? ""}`,
      SPLICE_SHARE_DIR: share,
      SPLICE_BIN_DIR: bin,
      SPLICE_NO_JAVA_DOWNLOAD: "1",
    },
  });
  const out = `${r.stdout ?? ""}${r.stderr ?? ""}`;

  expect(r.status, out).toBe(1);
  expect(out, "the refusal names the sign-in check").toContain("release local: the sign-in check failed; nothing was installed");
  expect(out, "the sign-in check's own lines are shown").toMatch(/^signin: FAIL /m);
  expect(existsSync(share) ? readdirSync(share) : [], "nothing was installed").toEqual([]);
  expect(existsSync(bin) ? readdirSync(bin) : [], "no command was linked").toEqual([]);
});
