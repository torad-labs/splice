#!/usr/bin/env bun
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { coveragePlan, gradleArgumentsForShard } from "./plan.ts";
import { collectCoverage, retainCoverage } from "./artifacts.ts";
import { layout } from "../lib/repo.ts";
import { runUnderSlot } from "../lib/slot.ts";

const [requested, ...extra] = process.argv.slice(2);
if (!requested || extra.length || !/^(?:[0-3]|report)$/.test(requested)) {
  console.error("usage: bun tools/gate/src/ci-coverage/run.ts <0|1|2|3|report>");
  process.exit(2);
}
const paths = layout(), root = paths.repoRoot;
const git = Bun.spawnSync(["git", "rev-parse", "HEAD"], { cwd: root });
if (git.exitCode !== 0) throw new Error("Cannot bind coverage candidate");
const candidate = git.stdout.toString().trim();
const env: Record<string, string> = {
  GITHUB_TOKEN: "", GH_TOKEN: "", ACTIONS_ID_TOKEN_REQUEST_TOKEN: "", ACTIONS_ID_TOKEN_REQUEST_URL: "",
};
let args: readonly string[];
if (requested === "report") {
  const binaries = collectCoverage(root, join(root, "coverage-binaries"), candidate);
  const manifest = join(root, ".ci/coverage-inputs.txt");
  mkdirSync(join(root, ".ci"), { recursive: true });
  writeFileSync(manifest, binaries.join("\n"));
  env.KOVER_EXTERNAL_BINARY_REPORTS = manifest;
  args = ["--no-build-cache", "--no-configuration-cache", ":koverXmlReport", ":koverHtmlReport"];
} else {
  const settings = readFileSync(join(root, "settings.gradle.kts"), "utf8");
  console.error("coverage shard " + requested + ": " + coveragePlan(settings)[Number(requested)]!.reports.join(" "));
  args = gradleArgumentsForShard(Number(requested), settings);
}
const rc = await runUnderSlot({ layout: paths, label: "ci-coverage", args, env });
if (rc === 0 && requested !== "report") retainCoverage(root, join(root, ".ci/coverage", requested), Number(requested), candidate);
process.exit(rc);
