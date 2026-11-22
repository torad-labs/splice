// `release local [--jar PATH]` — local install for a build on one machine without a version bump
// (signin-e2e). Runs the sign-in check against the candidate jar FIRST; if it fails, nothing is
// installed and the refusal names the check. When it passes, stages a flat local bundle and runs
// install.sh against it.
import { createHash } from "node:crypto";
import { chmodSync, copyFileSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

export const usage =
  "local [--jar PATH]                   check every provider's sign-in on a local jar, then install it with install.sh";

function sha256(path: string): string {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

export async function localInstall(argv: readonly string[], repoRoot: string): Promise<number> {
  let jarArg: string | undefined;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]!;
    if (arg === "--jar") {
      const value = argv[++i];
      if (!value) {
        console.error("release local: --jar requires a path");
        return 2;
      }
      jarArg = value;
    } else {
      console.error(`release local: unknown argument ${arg}`);
      return 2;
    }
  }
  const jar = resolve(jarArg ?? join(repoRoot, "app/build/libs/app-all.jar"));
  if (!existsSync(jar)) {
    console.error(`release local: jar not found at ${jar}`);
    return 1;
  }

  const e2eCli = join(repoRoot, "tools/e2e/index.ts");
  const check = Bun.spawnSync(["bun", e2eCli, "signin", "--jar", jar], {
    cwd: repoRoot,
    env: Bun.env,
    stdout: "inherit",
    stderr: "inherit",
  });
  if (check.exitCode !== 0) {
    console.error("release local: the sign-in check failed; nothing was installed");
    return 1;
  }

  const shim = join(repoRoot, "app/src/main/dist/bin/splice-launch");
  const installer = join(repoRoot, "install.sh");
  if (!existsSync(shim) || !existsSync(installer)) {
    console.error("release local: missing splice-launch or install.sh in repository");
    return 1;
  }

  const stage = mkdtempSync(join(tmpdir(), "splice-release-local-"));
  try {
    copyFileSync(jar, join(stage, "splice.jar"));
    copyFileSync(shim, join(stage, "splice-launch"));
    chmodSync(join(stage, "splice-launch"), 0o755);
    copyFileSync(installer, join(stage, "install.sh"));
    chmodSync(join(stage, "install.sh"), 0o755);
    const sums = ["splice.jar", "splice-launch", "install.sh"]
      .map((name) => `${sha256(join(stage, name))}  ${name}\n`)
      .join("");
    writeFileSync(join(stage, "sha256sums.txt"), sums);

    const run = Bun.spawnSync(["bash", join(stage, "install.sh")], {
      cwd: repoRoot,
      env: {
        ...Bun.env,
        SPLICE_RELEASE_BASE_URL: `file://${stage}`,
      },
      stdout: "inherit",
      stderr: "inherit",
    });
    return run.exitCode ?? 1;
  } finally {
    rmSync(stage, { recursive: true, force: true });
  }
}
