export interface CoverageShard {
  readonly id: number;
  readonly reports: readonly string[];
}

/**
 * Kover is applied only to the Kotlin/JVM projects. The native hub is intentionally
 * excluded: it has linuxX64Test rather than a Gradle Test task and the existing
 * aggregated Kover run does not instrument it.
 */
export const JVM_COVERAGE_MODULES = [
  "core",
  "integrations-claude-code",
  "integrations-mcp",
  "integrations-http",
  "integrations-topology",
  "integrations-codemode",
  "integrations-oauth",
  "integrations-terminal",
  "integrations-tmux",
  "integrations-daemon-client",
  "integrations-upstream",
  "integrations-dialects-anthropic",
  "integrations-dialects-openai-responses",
  "integrations-dialects-openai-chat",
  "integrations-providers-anthropic",
  "integrations-providers-codex",
  "integrations-providers-grok",
  "integrations-providers-kimi",
  "integrations-providers-muse",
  "integrations-providers-openai",
  "features-turns",
  "features-sessions",
  "features-models",
  "features-heads",
  "features-accounts",
  "features-usage",
  "features-lifecycle",
  "features-diagnostics",
  "features-launch",
  "features-configuration",
  "features-share",
  "app",
  "quality-architecture",
  "quality-compiler-plugin",
] as const;

const NATIVE_ONLY_MODULES = ["integrations-mcp-hub"] as const;

const SHARD_MODULES = [
  ["app"],
  [
    "features-turns",
    "core",
    "integrations-oauth",
    "integrations-dialects-openai-responses",
    "features-lifecycle",
    "features-usage",
    "features-diagnostics",
  ],
  ["quality-architecture", "integrations-claude-code", "features-heads"],
  [
    "integrations-mcp",
    "integrations-http",
    "integrations-topology",
    "integrations-codemode",
    "integrations-terminal",
    "integrations-tmux",
    "integrations-daemon-client",
    "integrations-upstream",
    "integrations-dialects-anthropic",
    "integrations-dialects-openai-chat",
    "integrations-providers-anthropic",
    "integrations-providers-codex",
    "integrations-providers-grok",
    "integrations-providers-kimi",
    "integrations-providers-muse",
    "integrations-providers-openai",
    "features-sessions",
    "features-models",
    "features-accounts",
    "features-launch",
    "features-configuration",
    "features-share",
    "quality-compiler-plugin",
  ],
] as const;

function binaryReportTask(module: string): string {
  return ":" + module + ":koverBinaryReport";
}

export const COVERAGE_REPORT_TASKS = JVM_COVERAGE_MODULES.map(binaryReportTask);

function modulesIncludedBy(settingsSource: string): readonly string[] {
  const include = settingsSource.match(/^include\(\s*([\s\S]*?)\s*\)/m);
  if (!include?.[1]) throw new Error("coverage ownership cannot read settings include()");

  return Array.from(include[1].matchAll(/"(:[^"]+)"/g), (match) => match[1]!.slice(1));
}

function assertShardOwnership(): void {
  const owners = SHARD_MODULES.flat();
  const duplicate = owners.find((module, index) => owners.indexOf(module) !== index);
  if (duplicate) throw new Error("coverage ownership duplicates " + duplicate);

  const missing = JVM_COVERAGE_MODULES.filter((module) => !owners.includes(module));
  if (missing.length > 0) throw new Error("coverage ownership missing " + missing.join(", "));

  const unexpected = owners.filter((module) => !JVM_COVERAGE_MODULES.includes(module as never));
  if (unexpected.length > 0) throw new Error("coverage ownership has undeclared modules " + unexpected.join(", "));
}

function assertSettingsOwnership(settingsSource: string): void {
  const included = modulesIncludedBy(settingsSource);
  const missing = JVM_COVERAGE_MODULES.filter((module) => !included.includes(module));
  if (missing.length > 0) throw new Error("coverage ownership missing " + missing.join(", "));

  const known = new Set<string>([...JVM_COVERAGE_MODULES, ...NATIVE_ONLY_MODULES]);
  const undecided = included.filter((module) => !known.has(module));
  if (undecided.length > 0) throw new Error("no coverage ownership for " + undecided.join(", "));
}

export function coveragePlan(settingsSource: string): readonly CoverageShard[] {
  assertShardOwnership();
  assertSettingsOwnership(settingsSource);

  return SHARD_MODULES.map((modules, id) => ({
    id,
    reports: modules.map(binaryReportTask),
  }));
}

/**
 * Kover's project binary report includes every Test task configured in that project.
 * Keeping a project together therefore preserves its law, isolated-thread, and
 * packaged test task configuration without class filters or report collisions.
 */
export function coverageReportForTestTask(testTask: string): string {
  const module = testTask.match(/^:(.+):[^:]+$/)?.[1];
  if (!module || !JVM_COVERAGE_MODULES.includes(module as never)) {
    throw new Error("no JVM Kover coverage report owns " + testTask);
  }
  return binaryReportTask(module);
}

export function gradleArgumentsForShard(shard: number, settingsSource: string): readonly string[] {
  const selected = coveragePlan(settingsSource)[shard];
  if (!selected) throw new Error("coverage shard must be 0 through 3 (got " + shard + ")");

  return ["--no-build-cache", "--console=plain", "--continue", ...selected.reports];
}
