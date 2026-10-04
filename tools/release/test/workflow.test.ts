// The release workflow's three parsed legs (checks/oss/verify-OSS-D.sh:12-25, :40-66, :70-92),
// each run against the REAL .github/workflows/release.yml and then against a mutant that must
// turn it red. The comment decoy is the one that matters: the legs were greps until 2026-08-31,
// and a comment satisfied them while the parsed step was ungated.
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import {
  commentDecoy,
  GATED_STAGE_RUN,
  parseWorkflow,
  prereleaseProblem,
  releaseFilesProblem,
  stageStepProblem,
  type Workflow,
} from "../src/lib/workflow.ts";
import { readManifest } from "../src/lib/dist.ts";

const { repoRoot } = layout();
const text = readFileSync(join(repoRoot, ".github/workflows/release.yml"), "utf8");

function parsed(source: string): Workflow {
  const workflow = parseWorkflow(source);
  expect(workflow, "the release workflow must parse").not.toBeInstanceOf(Error);
  return workflow as Workflow;
}

/** The staged asset set, from the manifest when a bundle is staged, else the published order. */
function assets(): readonly string[] {
  const manifest = readManifest(join(repoRoot, "dist"));
  if (!(manifest instanceof Error)) return manifest.assets;
  return [
    "splice.jar", "splice-launch", "install.sh",
    "LICENSE", "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_LICENSES.txt", "PROVENANCE.md",
    "bom.cdx.json", "dependency-licenses.json",
  ];
}

describe("release.yml, parsed", () => {
  test("the real workflow passes all three legs", () => {
    const workflow = parsed(text);
    expect(prereleaseProblem(workflow)).toBeNull();
    expect(stageStepProblem(workflow)).toBeNull();
    expect(releaseFilesProblem(workflow, assets())).toBeNull();
  });

  test("the workflow spells the gated stage command exactly once, as text as well as parsed", () => {
    expect(text.split(GATED_STAGE_RUN).length - 1).toBe(1);
  });

  // THE MUTANT: the gated line moved into a trailing comment. A grep still sees it; the parse does
  // not, and the step it does see is the bare, ungated command.
  test("the comment decoy passes a text check and fails this one", () => {
    const decoyed = commentDecoy(text);
    expect(decoyed).not.toBe(text);
    expect(decoyed).toContain(GATED_STAGE_RUN); // a grep-shaped check would still say yes
    const problem = stageStepProblem(parsed(decoyed));
    expect(problem).toContain("not the gated promotion command");
  });

  test("a second stage step, or none, fails the count", () => {
    const twice = text.replace(
      "      - name: accept exact release bundle",
      `      - name: stage again\n        run: ${GATED_STAGE_RUN}\n\n      - name: accept exact release bundle`,
    );
    expect(stageStepProblem(parsed(twice))).toContain("found 2");
    const none = text.replace(GATED_STAGE_RUN, "true");
    expect(stageStepProblem(parsed(none))).toContain("found 0");
  });

  test("dropping the resolved version from the stage step's env fails", () => {
    const unthreaded = text.replace(
      "        env:\n          RESOLVED_VERSION: ${{ steps.version.outputs.version }}\n        run: SPLICE_RELEASE_TAG",
      "        env:\n          RESOLVED_VERSION: 0.0.0\n        run: SPLICE_RELEASE_TAG",
    );
    expect(stageStepProblem(parsed(unthreaded))).toContain("does not thread the resolved version");
  });

  test("a hardcoded prerelease input fails", () => {
    const hardcoded = text.replace(
      "prerelease: ${{ contains(needs.build.outputs.version, '-') }}",
      "prerelease: false",
    );
    expect(prereleaseProblem(parsed(hardcoded))).toContain("prerelease input is");
  });

  test("an asset dropped from the upload list fails against the staged set", () => {
    const dropped = text.replace("            dist/PROVENANCE.md\n", "");
    expect(releaseFilesProblem(parsed(dropped), assets())).toContain("!= staged assets");
  });

  test("an asset added to the staged set but not to the workflow fails", () => {
    expect(releaseFilesProblem(parsed(text), [...assets(), "NEW-ASSET.txt"])).toContain("!= staged assets");
  });

  test("a non-mapping document is refused rather than half-checked", () => {
    expect(parseWorkflow("- one\n- two\n")).toBeInstanceOf(Error);
    expect(parseWorkflow("jobs: 3\n")).toBeInstanceOf(Error);
  });
});
