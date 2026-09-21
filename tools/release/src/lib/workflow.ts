// THE RELEASE WORKFLOW, PARSED — the three legs checks/oss/verify-OSS-D.sh ran through python+yaml.
//
// DR-19 redo (2026-08-31, codex catch): the original legs were raw greps, so a COMMENT satisfied
// them while the parsed step was an ungated bare command. These read the workflow as YAML instead:
// comments never survive a parse, so the whole decoy class is structurally dead, and
// `commentDecoy()` below is the mutant that proves it every run.
//
// Bun.YAML.parse returns a DIFFERENT SHAPE per document count — a single mapping is an object, a
// stream of two is an array (tools/gate/src/lib/ruledocs.ts:14). A workflow file is one document by
// definition (actions/runner parses it as one), so a non-object parse is a refusal here rather than
// an `Array.isArray` dance that would silently check document 0 of a file nobody meant to write.
import { readFileSync } from "node:fs";

export interface WorkflowStep {
  readonly uses?: unknown;
  readonly run?: unknown;
  readonly env?: Record<string, unknown>;
  readonly with?: Record<string, unknown>;
}
export interface Workflow {
  readonly jobs: Record<string, { readonly steps?: readonly WorkflowStep[] }>;
}

/** The publish step's action, pinned by SHA in the workflow; matched by prefix as the script did. */
export const RELEASE_ACTION = "softprops/action-gh-release@";
/** DR-19: the promotion path must run the stage task under the resolved version as the tag. */
export const GATED_STAGE_RUN =
  'SPLICE_RELEASE_TAG="v$RESOLVED_VERSION" ./gradlew :app:stageRelease --no-daemon --no-parallel';
/** What makes a step "the stage step", independently of how it is spelled. */
export const STAGE_TASK = ":app:stageRelease";
export const RESOLVED_VERSION = "${{ steps.version.outputs.version }}";
export const PRERELEASE_INPUT = "${{ contains(needs.build.outputs.version, '-') }}";

export function parseWorkflow(text: string): Workflow | Error {
  let parsed: unknown;
  try {
    parsed = Bun.YAML.parse(text);
  } catch (error) {
    return new Error(`release verify: the release workflow is not parseable YAML: ${String(error)}`);
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return new Error("release verify: the release workflow is not a single YAML mapping");
  }
  const jobs = (parsed as { jobs?: unknown }).jobs;
  if (typeof jobs !== "object" || jobs === null) return new Error("release verify: the release workflow declares no jobs");
  return { jobs: jobs as Workflow["jobs"] };
}

export function readWorkflow(path: string): Workflow | Error {
  try {
    return parseWorkflow(readFileSync(path, "utf8"));
  } catch {
    return new Error(`release verify: cannot read the release workflow at ${path}`);
  }
}

function steps(workflow: Workflow, job: string): readonly WorkflowStep[] {
  return workflow.jobs[job]?.steps ?? [];
}

function everyStep(workflow: Workflow): readonly WorkflowStep[] {
  return Object.values(workflow.jobs).flatMap((job) => job.steps ?? []);
}

/** Exactly one action-gh-release step, and its `prerelease` input is the version-derived expression. */
export function prereleaseProblem(workflow: Workflow): string | null {
  const releaseSteps = steps(workflow, "publish").filter((step) => String(step.uses ?? "").startsWith(RELEASE_ACTION));
  if (releaseSteps.length !== 1) {
    return `release verify: expected one action-gh-release step, found ${releaseSteps.length}`;
  }
  const actual = releaseSteps[0]!.with?.prerelease;
  if (actual !== PRERELEASE_INPUT) {
    return `release verify: action-gh-release prerelease input is ${JSON.stringify(actual)}, expected ${JSON.stringify(PRERELEASE_INPUT)}`;
  }
  return null;
}

/**
 * DR-19: the tag gate must run on the PROMOTION path too — the stage step threads the resolved
 * version in as SPLICE_RELEASE_TAG (a real pushed tag still wins inside the task via
 * GITHUB_REF_TYPE). Without this wiring the whole SemVer/equality block is skipped exactly where
 * releases are cut, and a malformed launcher version lands as the tag name.
 */
export function stageStepProblem(workflow: Workflow): string | null {
  const stage = everyStep(workflow).filter((step) => String(step.run ?? "").includes(STAGE_TASK));
  if (stage.length !== 1) {
    return `release verify: expected exactly one parsed ${STAGE_TASK} step, found ${stage.length}`;
  }
  const step = stage[0]!;
  if (step.run !== GATED_STAGE_RUN) {
    return `release verify: stage step run is not the gated promotion command: ${JSON.stringify(step.run)}`;
  }
  if (step.env?.RESOLVED_VERSION !== RESOLVED_VERSION) {
    return `release verify: stage step env does not thread the resolved version: ${JSON.stringify(step.env)}`;
  }
  return null;
}

/**
 * DR-25: the release's upload list must be the STAGED set, dist/-prefixed, plus the sums file — an
 * asset dropped from the workflow ships absent under a green build otherwise. `assets` comes from
 * the manifest `:app:stageRelease` wrote, never from a second hand copy of the list.
 */
export function releaseFilesProblem(workflow: Workflow, assets: readonly string[]): string | null {
  const release = steps(workflow, "publish").find((step) => String(step.uses ?? "").startsWith(RELEASE_ACTION));
  if (!release) return "release verify: the publish job has no action-gh-release step";
  const files = String(release.with?.files ?? "").split(/\s+/).filter((entry) => entry !== "");
  const expected = [...assets.map((asset) => `dist/${asset}`), "dist/sha256sums.txt"];
  if (files.length !== expected.length || files.some((file, i) => file !== expected[i])) {
    return `release verify: release.yml files ${JSON.stringify(files)} != staged assets ${JSON.stringify(expected)}`;
  }
  return null;
}

/**
 * The comment decoy, as a transformation: the gated run line is moved into a trailing comment, so a
 * TEXT check still sees it and a PARSE sees the bare command. `stageStepProblem` must reject the
 * result — that is the proof the leg can fail on exactly the class it was rewritten for.
 */
export function commentDecoy(text: string): string {
  const gated = `run: ${GATED_STAGE_RUN}`;
  const bare = `run: ./gradlew ${STAGE_TASK} --no-daemon --no-parallel # ${GATED_STAGE_RUN}`;
  return text.replace(gated, bare);
}
