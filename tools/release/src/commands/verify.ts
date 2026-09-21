// `release verify` — THE RELEASE REHEARSAL (the PROCESS legs of checks/oss/verify-OSS-D.sh until
// PR 6). It runs what a real release run would do, plus the mutants that prove each leg can fail.
//
// The legs, in order, fail-fast as the script was:
//   1. install.sh is parseable, and shellcheck-clean. shellcheck is not a tree dependency, but a
//      box without it on PATH is a refusal naming the install remedy — never a skipped leg (the
//      script this replaced exited 127 under `set -e` when the binary was missing);
//   2. the launch shim's rehearsal (src/lib/launcher.ts);
//   3. the release workflow, PARSED: one action-gh-release step with the version-derived
//      `prerelease`, exactly one parsed stage step carrying the gated command and the resolved
//      version, plus the comment-decoy mutant that proves the parse cannot be fooled by a comment;
//   4. the SemVer/tag-equality mutants, driven through the REAL `:app:stageRelease` task;
//   5. the rehearsal proper — build the fat jar through the slot, stage, accept;
//   6. release.yml's `files:` list against the STAGED manifest (DR-25), and the dist enumeration
//      against the actual directory, with its four mutants (undispositioned extra file, blank
//      reason, placeholder reason, substantive-reason positive control) and the zero-byte artifact
//      that must red on accept's EMPTY leg.
//
// The jar is BUILT here, through the slot, exactly as verify-OSS-D.sh did: this verb runs in the
// gate's post-slot phase, where the slot is free, and a rehearsal that assumes someone else's
// artifact is rehearsing someone else's release.
import { copyFileSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { SUMS, enumerationProblem, readManifest } from "../lib/dist.ts";
import { launcherRehearsal } from "../lib/launcher.ts";
import { shimPath } from "../lib/shim.ts";
import {
  commentDecoy,
  parseWorkflow,
  prereleaseProblem,
  releaseFilesProblem,
  stageStepProblem,
} from "../lib/workflow.ts";

export const usage =
  "verify                               the release rehearsal: install.sh, the launch shim, release.yml, the stage/accept mutants, then stage + accept for real";

/** The label this verb takes the gradle slot under. */
const SLOT_LABEL = "release-verify";
const WORKFLOW = ".github/workflows/release.yml";

function leg(name: string): void {
  console.error(`── ${name} ──`);
}

function fail(message: string): number {
  console.error(message);
  return 1;
}

interface Ran {
  readonly ok: boolean;
  readonly output: string;
}

function run(repoRoot: string, argv: readonly string[], env: Record<string, string> = {}): Ran {
  const proc = Bun.spawnSync([...argv], {
    cwd: repoRoot,
    env: { ...Bun.env, ...env },
    stdout: "pipe",
    stderr: "pipe",
  });
  return { ok: proc.exitCode === 0, output: `${proc.stdout.toString()}${proc.stderr.toString()}` };
}

/** gradle, always through the slot: two gradles in one project dir hand each other false reds. */
function gradle(repoRoot: string, args: readonly string[], env: Record<string, string> = {}): Ran {
  return run(repoRoot, [process.execPath, join(repoRoot, "tools", "gate", "index.ts"), "slot", SLOT_LABEL, "--", ...args], env);
}

function acceptCli(repoRoot: string, distDir: string, version: string): Ran {
  return run(repoRoot, [process.execPath, join(repoRoot, "tools", "release", "index.ts"), "accept", distDir], {
    SPLICE_EXPECTED_VERSION: version,
  });
}

export async function verify(argv: readonly string[], repoRoot: string): Promise<number> {
  if (argv.length > 0) return fail(`release verify: takes no arguments (got ${argv.join(" ")})`);
  const version = (JSON.parse(readFileSync(join(repoRoot, "package.json"), "utf8")) as { version: string }).version;

  leg("install.sh");
  const parsed = run(repoRoot, ["bash", "-n", "install.sh"]);
  if (!parsed.ok) return fail(`release verify: install.sh is not parseable by bash\n${parsed.output}`);
  if (!Bun.which("shellcheck")) {
    return fail(
      "release verify: shellcheck is not on PATH — install it (`apt-get install shellcheck` on Debian/Ubuntu, `brew install shellcheck` on macOS) and re-run; the script this replaced exited 127 under `set -e` when it was missing, and this leg refuses the same way rather than skipping the lint.",
    );
  }
  const shellcheck = run(repoRoot, ["shellcheck", "-S", "error", "install.sh"]);
  if (!shellcheck.ok) return fail(`release verify: shellcheck -S error install.sh\n${shellcheck.output}`);

  leg("the launch shim");
  const launcher = await launcherRehearsal(shimPath(repoRoot));
  if (launcher) return fail(launcher);
  console.error("launcher test: OK");

  leg("release.yml");
  const workflowPath = join(repoRoot, WORKFLOW);
  const workflowText = readFileSync(workflowPath, "utf8");
  const workflow = parseWorkflow(workflowText);
  if (workflow instanceof Error) return fail(workflow.message);
  for (const problem of [prereleaseProblem(workflow), stageStepProblem(workflow)]) {
    if (problem) return fail(problem);
  }
  // The mutant that keeps the parse honest: the gated line moved into a comment satisfies a TEXT
  // check and must NOT satisfy this one.
  const decoy = parseWorkflow(commentDecoy(workflowText));
  if (decoy instanceof Error) return fail(decoy.message);
  if (stageStepProblem(decoy) === null) {
    return fail("release verify: comment-decoy mutant unexpectedly passed — the stage-step check cannot fail");
  }

  leg("the tag gate");
  // DR-19 companion: a mismatched-but-VALID tag must fail for the equality reason specifically —
  // the old leg fed the tag from package.json, so agreement could never fail from this caller.
  const tagMutants: readonly (readonly [string, string])[] = [
    ["v1.2.3-01", "tag must be valid SemVer"],
    ["v1.2.3-alpha.007", "tag must be valid SemVer"],
    ["v0.0.0-mismatch", "does not match package version"],
  ];
  for (const [tag, reason] of tagMutants) {
    const mutant = gradle(repoRoot, ["-q", ":app:stageRelease", "--no-parallel"], { SPLICE_RELEASE_TAG: tag });
    if (mutant.ok) return fail(`release verify: invalid release tag unexpectedly passed: ${tag}`);
    if (!mutant.output.includes(reason)) {
      return fail(`release verify: ${tag} failed for the wrong reason (expected "${reason}")\n${mutant.output}`);
    }
  }

  leg("stage + accept");
  // Through the slot: --no-daemon comes from the slot, --no-parallel stays.
  const built = gradle(repoRoot, ["-q", ":app:shadowJar", "--no-parallel"]);
  if (!built.ok) return fail(`release verify: :app:shadowJar failed\n${built.output}`);
  const staged = gradle(repoRoot, ["-q", ":app:stageRelease", "--no-parallel"], { SPLICE_RELEASE_TAG: `v${version}` });
  if (!staged.ok) return fail(`release verify: :app:stageRelease failed\n${staged.output}`);
  process.stderr.write(staged.output);
  const accepted = acceptCli(repoRoot, join(repoRoot, "dist"), version);
  process.stderr.write(accepted.output);
  if (!accepted.ok) return fail("release verify: the staged bundle was not accepted");

  leg("the staged denominator");
  const distDir = join(repoRoot, "dist");
  const manifest = readManifest(distDir);
  if (manifest instanceof Error) return fail(manifest.message);
  const published = [...manifest.assets, SUMS];
  const filesProblem = releaseFilesProblem(workflow, manifest.assets);
  if (filesProblem) return fail(filesProblem);
  // DR-25 redo (codex catch, 2026-08-31): the asset denominator used to come from stage's hand
  // list, so a staged file OUTSIDE that list shipped absent from checksums/release under a green
  // verifier. The denominator is the ACTUAL dist/.
  const real = enumerationProblem(distDir, published);
  if (real) return fail(real);

  const mutantDist = mkdtempSync(join(tmpdir(), "release-verify-dist-"));
  try {
    for (const name of readdirSync(distDir)) {
      copyFileSync(join(distDir, name), join(mutantDist, name));
    }
    writeFileSync(join(mutantDist, "EXTRA-README.md"), "stray\n");
    const extra = enumerationProblem(mutantDist, published);
    if (!extra) return fail("release verify: extra-file mutant unexpectedly passed — the enumeration cannot fail");
    if (!extra.includes("NO disposition")) {
      return fail(`release verify: extra-file mutant failed for the wrong reason (not the disposition leg): ${extra}`);
    }
    // §24: a blank/placeholder exclusion reason is absence, not a disposition. EXTRA-README.md is
    // still present; excluding it with an EMPTY reason must RED on the blank-reason leg — never
    // silently disposition the file. The positive control that follows uses a NONBLANK reason,
    // proving the leg is not vacuously red.
    for (const reason of ["", "TODO"]) {
      const problem = enumerationProblem(mutantDist, published, { "EXTRA-README.md": reason });
      if (!problem) {
        return fail(`release verify: a ${reason === "" ? "blank" : "placeholder"} exclusion reason dispositioned a file`);
      }
      if (!problem.includes("blank or placeholder reason")) {
        return fail(`release verify: the ${reason === "" ? "blank" : "placeholder"}-reason mutant failed for the wrong reason: ${problem}`);
      }
    }
    const substantive = enumerationProblem(mutantDist, published, {
      "EXTRA-README.md": "see TODO-1234: excluded until the platform port lands",
    });
    if (substantive) {
      return fail(`release verify: a substantive reason (even one mentioning TODO) must disposition the file and pass (positive control): ${substantive}`);
    }
    // The zero-byte artifact, whose red must be accept's EMPTY leg and not a checksum coincidence.
    rmSync(join(mutantDist, "EXTRA-README.md"));
    writeFileSync(join(mutantDist, "PROVENANCE.md"), "");
    const empty = acceptCli(repoRoot, mutantDist, version);
    if (empty.ok) return fail("release verify: empty-artifact mutant unexpectedly passed accept");
    if (!empty.output.includes("EMPTY")) {
      return fail(`release verify: empty-artifact mutant failed for the wrong reason (not the EMPTY leg)\n${empty.output}`);
    }
  } finally {
    rmSync(mutantDist, { recursive: true, force: true });
  }

  console.log("release verify: OK");
  return 0;
}
