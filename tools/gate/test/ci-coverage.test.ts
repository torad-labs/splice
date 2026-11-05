import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  COVERAGE_REPORT_TASKS,
  coveragePlan,
  coverageReportForTestTask,
  gradleArgumentsForShard,
} from "../src/ci-coverage/plan.ts";
import { layout } from "../src/lib/repo.ts";

const root = layout().repoRoot;
const settings = readFileSync(root + "/settings.gradle.kts", "utf8");

test("the four coverage workers own every JVM binary coverage report exactly once", () => {
  const plan = coveragePlan(settings);
  expect(plan).toHaveLength(4);

  const owned = plan.flatMap((shard) => shard.reports);
  expect(new Set(owned).size).toBe(owned.length);
  expect([...owned].sort()).toEqual([...COVERAGE_REPORT_TASKS].sort());
  expect(owned).not.toContain(":integrations-mcp-hub:koverBinaryReport");
});

test("every existing additional JVM Test family stays with its module's one instrumented binary report", () => {
  expect(coverageReportForTestTask(":integrations-topology:lawTest")).toBe(
    ":integrations-topology:koverBinaryReport",
  );
  expect(coverageReportForTestTask(":features-lifecycle:lawTest")).toBe(
    ":features-lifecycle:koverBinaryReport",
  );
  expect(coverageReportForTestTask(":features-diagnostics:lawTest")).toBe(
    ":features-diagnostics:koverBinaryReport",
  );
  expect(coverageReportForTestTask(":app:lawTest")).toBe(":app:koverBinaryReport");
  expect(coverageReportForTestTask(":integrations-upstream:threadRefusalTest")).toBe(
    ":integrations-upstream:koverBinaryReport",
  );
  expect(coverageReportForTestTask(":quality-architecture:treeLawTest")).toBe(
    ":quality-architecture:koverBinaryReport",
  );
  expect(coverageReportForTestTask(":app:codeModePackagedTest")).toBe(":app:koverBinaryReport");
});

test("a changed module map fails closed until coverage ownership is declared", () => {
  const changed = settings.replace(
    '":quality-compiler-plugin",',
    '":quality-compiler-plugin", ":new-jvm-module",',
  );
  expect(() => coveragePlan(changed)).toThrow("no coverage ownership");
  expect(() => coveragePlan('include(":core")')).toThrow("missing");
});

test("each shard requests only its own instrumented binary reports without the build cache", () => {
  for (let shard = 0; shard < 4; shard++) {
    const args = gradleArgumentsForShard(shard, settings);
    expect(args).toContain("--no-build-cache");
    expect(args).toContain("--continue");
    expect(args).not.toContain(":koverBinaryReport");
    expect(args.filter((arg) => arg.endsWith(":koverBinaryReport"))).toEqual(
      [...coveragePlan(settings)[shard]!.reports],
    );
  }
});

test("coverage reports remain downloadable for forks while write access is isolated to same-repository comments", () => {
  const workflow = readFileSync(root + "/.github/workflows/coverage.yml", "utf8");
  const workers = workflow.slice(workflow.indexOf("  coverage:"), workflow.indexOf("  report:"));
  const report = workflow.slice(workflow.indexOf("  report:"), workflow.indexOf("  comment:"));
  const comment = workflow.slice(workflow.indexOf("  comment:"));

  expect(workers).toContain("matrix:");
  expect(workers).toContain("shard: [0, 1, 2, 3]");
  expect(workers).toContain("GITHUB_TOKEN: \"\"");
  expect(workers).toContain("actions/upload-artifact@");
  expect(report).not.toContain("pull-requests: write");
  expect(report).toContain("actions/download-artifact@");
  expect(comment).toContain("github.event.pull_request.head.repo.full_name == github.repository");
  expect(comment).toContain("pull-requests: write");
});
