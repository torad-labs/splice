// `release verify`'s wiring. Its legs are proven in dist/workflow/launcher/accept; what is pinned
// here is that the release path SPELLS these verbs — release.yml and package.json are where a port
// silently leaves the old entry behind, and a rehearsal nobody runs is not a rehearsal.
import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { GATED_STAGE_RUN, tagMutantEnv } from "../src/lib/workflow.ts";

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
    expect(build).toContain('repositoryRoot.file("docs/PROVENANCE.md")');
    expect(build).toContain('layout.projectDirectory.file("src/main/dist/bin/splice-launch")');
  });

  test("the shim the build stages is the shim every reader resolves", () => {
    expect(existsSync(join(repoRoot, "app/src/main/dist/bin/splice-launch"))).toBe(true);
  });
});

describe("the tag mutants", () => {
  test("carry the mutant tag they are meant to feed in", () => {
    expect(tagMutantEnv("v1.2.3-01").SPLICE_RELEASE_TAG).toBe("v1.2.3-01");
  });

  // DR-19 runs the other way for a mutant. A real pushed tag beats SPLICE_RELEASE_TAG inside
  // stageRelease, so on a `v*` tag push the real and VALID tag would win over the bad tag this leg
  // feeds in: stageRelease succeeds and verify reports that an invalid tag passed. The failure is
  // the harness, and it appears only on the run that ships.
  test("neutralise a real pushed tag, so the mutant is the authority", () => {
    const env = tagMutantEnv("v0.0.0-mismatch");
    // PRESENT, not merely absent. The child runs under { ...Bun.env, ...env }, so an unset key
    // would leave CI's own GITHUB_REF_TYPE=tag in place and the neutralisation would do nothing.
    expect(Object.keys(env)).toContain("GITHUB_REF_TYPE");
    expect(Object.keys(env)).toContain("GITHUB_REF_NAME");
    expect(env.GITHUB_REF_TYPE).not.toBe("tag");
  });
});
