// `release verify`'s wiring. Its legs are proven in dist/workflow/launcher/accept; what is pinned
// here is that the release path SPELLS these verbs — release.yml and package.json are where a port
// silently leaves the old entry behind, and a rehearsal nobody runs is not a rehearsal.
import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { GATED_STAGE_RUN } from "../src/lib/workflow.ts";

const { repoRoot } = layout();
const read = (rel: string) => readFileSync(join(repoRoot, rel), "utf8");
const scripts = (JSON.parse(read("package.json")) as { scripts: Record<string, string> }).scripts;

describe("release verify", () => {
  test("it takes no arguments", () => {
    const proc = Bun.spawnSync([process.execPath, join(repoRoot, "tools", "release", "index.ts"), "verify", "--now"], {
      cwd: repoRoot,
      stdout: "pipe",
      stderr: "pipe",
    });
    expect(proc.exitCode).toBe(1);
    expect(proc.stderr.toString()).toContain("takes no arguments");
  });

  test("an unknown verb is a usage refusal, not a silent pass", () => {
    const proc = Bun.spawnSync([process.execPath, join(repoRoot, "tools", "release", "index.ts"), "publish"], {
      cwd: repoRoot,
      stdout: "pipe",
      stderr: "pipe",
    });
    expect(proc.exitCode).toBe(2);
    expect(proc.stderr.toString()).toContain('no such verb "publish"');
  });

  test("package.json's release scripts are these verbs", () => {
    expect(scripts["release:accept"]).toBe("bun tools/release accept");
    expect(scripts["release:verify"]).toBe("bun tools/release verify");
    expect(scripts.promote).toBe("bun tools/release promote");
    expect(scripts["gate:audit"]).toBe("bun tools/gate audit");
  });

  test("the release workflow stages through the Gradle task and accepts through this CLI", () => {
    const workflow = read(".github/workflows/release.yml");
    expect(workflow).toContain(`run: ${GATED_STAGE_RUN}`);
    expect(workflow).toContain('run: SPLICE_EXPECTED_VERSION="$RESOLVED_VERSION" bun tools/release accept');
    expect(workflow).not.toContain("checks/release/");
  });

  test("the staging task and its ONE asset list live in the app build", () => {
    const build = read("app/build.gradle.kts");
    expect(build).toContain('tasks.register("stageRelease")');
    expect(build).toContain("val releaseAssets = listOf(");
    // the two moved sources, read from one place each
    expect(build).toContain('repositoryRoot.file(".docs/PROVENANCE.md")');
    expect(build).toContain('layout.projectDirectory.file("src/main/dist/bin/splice-launch")');
  });

  test("the shim the build stages is the shim every reader resolves", () => {
    expect(existsSync(join(repoRoot, "app/src/main/dist/bin/splice-launch"))).toBe(true);
  });
});
