import { expect, test } from "bun:test";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pullRequestBase } from "../src/ci/pr-base.ts";
import { pathsSince } from "../src/commands/hook.ts";

function git(root: string, ...args: string[]): string {
  const result = Bun.spawnSync(["git", ...args], { cwd: root, env: { ...process.env, GIT_CONFIG_NOSYSTEM: "1", GIT_CONFIG_GLOBAL: "/dev/null", GIT_AUTHOR_NAME: "Fixture", GIT_AUTHOR_EMAIL: "fixture@example.invalid", GIT_COMMITTER_NAME: "Fixture", GIT_COMMITTER_EMAIL: "fixture@example.invalid" } });
  if (result.exitCode !== 0) throw new Error(result.stderr.toString());
  return result.stdout.toString().trim();
}
test("a two-generation checkout selects all PR changes even after long branch divergence", () => {
  for (const length of [1, 4, 11]) {
    const root = mkdtempSync("/tmp/splice-pr-base-");
    const shallow = root + "-shallow";
    try {
      git(root, "init", "-b", "main");
      writeFileSync(join(root, "deleted.txt"), "original");
      writeFileSync(join(root, "renamed.txt"), "rename me");
      git(root, "add", "."); git(root, "commit", "-m", "initial");
      const eventBase = git(root, "rev-parse", "HEAD");
      git(root, "checkout", "-b", "feature");
      for (let i = 0; i < length; i++) {
        writeFileSync(join(root, "feature.txt"), String(i));
        git(root, "add", "."); git(root, "commit", "-m", "feature");
      }
      git(root, "rm", "deleted.txt"); git(root, "mv", "renamed.txt", "new-name.txt");
      git(root, "commit", "-m", "rename and delete");
      const head = git(root, "rev-parse", "HEAD");
      git(root, "checkout", "main");
      writeFileSync(join(root, "base-only.txt"), "upstream advanced");
      git(root, "add", "."); git(root, "commit", "-m", "base");
      const base = git(root, "rev-parse", "HEAD");
      git(root, "merge", "--no-ff", "feature", "-m", "candidate");
      git(root, "clone", "--depth", "2", "--no-tags", "file://" + root, shallow);
      expect(git(shallow, "rev-parse", "--is-shallow-repository")).toBe("true");
      const selectedBase = pullRequestBase(shallow, head);
      const selected = pathsSince(shallow, selectedBase);
      expect(selected).toEqual(["deleted.txt", "feature.txt", "new-name.txt", "renamed.txt"]);
      expect(selected).toEqual(pathsSince(root, base));
      // GitHub can retain the PR-open base SHA after rebuilding its merge on newer main.
      expect(selectedBase).not.toBe(eventBase);
      expect(selectedBase).toBe(base);
      expect(() => pullRequestBase(shallow, base)).toThrow();
      expect(() => pullRequestBase(shallow, "0".repeat(40))).toThrow();
      expect(() => pullRequestBase(shallow, "--all")).toThrow();
      git(shallow, "checkout", "--detach", base);
      expect(() => pullRequestBase(shallow, head)).toThrow();
    } finally {
      rmSync(root, { recursive: true, force: true });
      rmSync(shallow, { recursive: true, force: true });
    }
  }
});
