// Verifies `release local [--jar PATH]` refuses to install when the sign-in check fails,
// prints the refusal naming the sign-in check, and leaves share/bin directories untouched.
import { afterAll, expect, setDefaultTimeout, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";

setDefaultTimeout(30_000);

const { repoRoot } = layout();
const dirs: string[] = [];
afterAll(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
});
const scratch = (prefix: string) => {
  const d = mkdtempSync(join(tmpdir(), prefix));
  dirs.push(d);
  return d;
};

test("release local refuses install when the sign-in check fails and installs nothing", () => {
  const home = scratch("release-local-home-");
  const jar = join(scratch("release-local-jar-"), "app-all.jar");
  writeFileSync(jar, "synthetic jar\n");
  const fakeJava = scratch("release-local-java-");
  writeFileSync(join(fakeJava, "java"), '#!/bin/sh\necho "no daemon in synthetic jar" >&2\nexit 1\n');
  chmodSync(join(fakeJava, "java"), 0o755);

  const share = join(home, ".local/share/splice");
  const bin = join(home, ".local/bin");
  const r = spawnSync("bun", ["tools/release", "local", "--jar", jar], {
    cwd: repoRoot,
    encoding: "utf8",
    env: {
      ...process.env,
      HOME: home,
      PATH: `${fakeJava}:${process.env.PATH ?? ""}`,
      SPLICE_SHARE_DIR: share,
      SPLICE_BIN_DIR: bin,
      SPLICE_NO_JAVA_DOWNLOAD: "1",
    },
  });
  const out = `${r.stdout ?? ""}${r.stderr ?? ""}`;
  expect(r.status, out).toBe(1);
  expect(out).toContain("release local: the sign-in check failed; nothing was installed");
  expect(out).toMatch(/^signin: FAIL /m);
  expect(existsSync(share) ? readdirSync(share) : []).toEqual([]);
  expect(existsSync(bin) ? readdirSync(bin) : []).toEqual([]);
});
