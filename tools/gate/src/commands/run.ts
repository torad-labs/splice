// `gate run` — the gate of record.
//
// `gradle gateOfRecord` is the task graph (build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts:
// every module's check, the console's lint and tests, and one Exec task per row of
// tools/gate/config/ladder.json); this is the boundary, and there is exactly one. Four things
// here are correctness rather than habit (checks/gate.sh:14-50 and :69-84 until PR 5):
//   - JDK 21 is resolved BEFORE any JVM starts, from the running launcher's own `java.home`, and a
//     box without one is a refusal naming the remedy — never a gradle that picks whatever it finds;
//   - `clean` + `--no-build-cache`, because Kotlin's compile-avoidance ABI snapshot does not track
//     `internal` members: on 2026-09-16 :app:compileKotlin and :app:compileTestKotlin were restored
//     FROM-CACHE from different source states and SpinnerTest died with AbstractMethodError. A gate
//     of record must never measure a mixture of two source states;
//   - in a tree of its own on this box (the gate tree under the git directory, moved to HEAD), so `clean` never
//     deletes another seat's build output and the run never holds the shared checkout's slot;
//   - through the SLOT, because two gradles in one project dir clobber build/ outputs and hand each
//     other false reds, and a gate that a neighbour's timing can turn red is not a gate of record.
// the daemon choice is the slot's own, as it is for every other caller. `--continue` is
// what gate.sh's `run` was: every leg runs and reports its own verdict, and the gate is red if any
// leg is — a ladder that stops at the first red hides every red behind it.
//
// AFTER THE SLOT: the legs that cannot run inside the graph. The release rehearsal
// (`bun tools/release verify`) builds and stages a release through the slot itself, and the slot
// is held for the whole graph — a nested run would wait on its own lock. It runs once the slot is
// released. GATE: PASS needs both phases green. (The OSS readiness scripts ran here until PR 6;
// their static assertions are ReleaseReadinessLawTest, their dependency audit a ladder row.)
//
// `--java-home-only` prints the resolved JAVA_HOME and stops: it is the resolver's own executable
// test — tools/gate/test/jdk.test.ts calls `run(["--java-home-only"])` directly, captures the
// printed home, and asserts javaMajor reports 21 for its `bin/java`.
import { join } from "node:path";
import { resolveJdk21 } from "../lib/jdk.ts";
import { GATE_TREE, type PrePushTree, preparePrePushTree } from "../lib/prepush-tree.ts";
import { type Layout, layout } from "../lib/repo.ts";
import { acquireRunSentinel, describeOpenRun } from "../lib/sentinel.ts";
import { runUnderSlot } from "../lib/slot.ts";
import { exitStatusOf } from "../lib/status.ts";

export const usage = "run [--java-home-only | --since <ref>]    the gate of record: JDK 21, then clean gateOfRecord through the slot, then the release rehearsal, locally over HEAD in the gate's own build tree; with --since, only what the diff against <ref> affects (pull requests)";

export const GATE_OF_RECORD_LABEL = "gate-of-record";
/** How long a local gate waits for another local gate to free the gate tree: a whole gate runs 20 to 40 minutes. */
const GATE_TREE_WAIT_MS = 60 * 60 * 1000;
/** `--profile` writes build/reports/profile/: each task's time, which CI keeps as an artifact, so where a
 *  gate's minutes go is read off the run rather than guessed from log timestamps. */
export const GATE_OF_RECORD_TASKS = ["--no-build-cache", "clean", "gateOfRecord", "--continue", "--profile"] as const;
/** The legs that run after the slot is released, because they take the slot themselves. */
export const AFTER_THE_SLOT: readonly { readonly label: string; readonly command: readonly string[] }[] = [
  { label: "release rehearsal", command: ["bun", "tools/release", "verify"] },
];

/** A slot phase that ended by SIGNAL (128+signum, the shell's contract in status.ts) is a cancelled
 *  gate, not a verdict: nothing runs after it. The first cut carried a 143 into the post-slot phase,
 *  whose scripts launch more gradle builds — so `kill <gate pid>` resumed work the operator had
 *  just stopped (#170 review). Gradle itself exits 0 or 1; the slot's own refusals are 2 and 75. */
export function cancelledBySignal(slotExit: number): boolean {
  return slotExit > 128;
}

/** EX_TEMPFAIL, the same class as the slot's own timeout exit: no verdict was produced and the
 *  caller should retry later. A caller acts identically on both, so they do not need distinct
 *  numbers — only a distinct MESSAGE, which they have. */
export const RUN_ALREADY_OPEN_EXIT = 75;

/** Recorded for a human reading the sentinel, never for liveness, and the sha a local gate checks out into its build tree. */
function headAtStart(repoRoot: string): string {
  const proc = Bun.spawnSync(["git", "rev-parse", "HEAD"], { cwd: repoRoot });
  return proc.exitCode === 0 ? proc.stdout.toString().trim() : "unknown";
}

/** Where a gate judges, and the lock its gradle takes. On CI, the runner's checkout of the sha. On this box, the gate's own
 *  build tree moved to HEAD, never the shared checkout: there `clean` deleted every seat's build output and a whole gate held
 *  the checkout's slot for 34 to 40 minutes while every other seat's build waited (buildgate footprints, Oct 9: 2439 s and
 *  2067 s), and its verdict covered whatever uncommitted edits other seats had in flight. The tree holds HEAD's committed
 *  bytes, as CI does, and its gradle (the rehearsal's nested gradle included) takes the lock beside the tree's build root. */
interface JudgedIn {
  readonly where: Layout;
  readonly lockEnv: Record<string, string>;
  readonly release: () => void;
}

function judgeIn(repoRoot: string, head: string): JudgedIn | { readonly error: string } {
  if (Bun.env.CI) return { where: layout(), lockEnv: {}, release: () => {} };
  let tree: PrePushTree;
  try {
    tree = preparePrePushTree(repoRoot, head, GATE_TREE_WAIT_MS, GATE_TREE);
  } catch (error) {
    return { error: `gate: cannot check HEAD out into the gate's build tree: ${error instanceof Error ? error.message : String(error)}` };
  }
  console.error(`gate: judging HEAD ${head.slice(0, 7)} in ${tree.path} (setup ${(tree.setupMs / 1000).toFixed(1)} s); the checkout's slot and build output are untouched`);
  return {
    where: { repoRoot: tree.path, buildRoot: tree.path },
    lockEnv: { GRADLE_SLOT_LOCK: join(tree.path, ".gradle-slot.lock") },
    release: tree.release,
  };
}

/** A pull request's gate: the same selector pre-push uses, over the branch's diff against its merge base with [base]. The
 *  full ladder is the push to main's, so nothing the diff does not touch is skipped there. */
async function runSince(base: string): Promise<number> {
  const jdk = resolveJdk21();
  if ("error" in jdk) {
    console.error(jdk.error);
    return 1;
  }
  const judged = judgeIn(layout().repoRoot, headAtStart(layout().repoRoot));
  if ("error" in judged) {
    console.error(judged.error);
    return 1;
  }
  try {
    const { repoRoot } = judged.where;
    const hook = await import("./hook.ts");
    const { prePushScope } = await import("../lib/prepush-scope.ts");
    const modules = hook.gradleModules(repoRoot);
    const legs = hook.readLadder(repoRoot);
    const scope = prePushScope({
      legs,
      modules: modules.map((module) => module.path),
      moduleOf: (file) => hook.moduleOf(modules, file),
      changed: hook.pathsSince(repoRoot, base),
      graph: hook.readModuleGraph(repoRoot),
    });
    console.error(`══ splice gate (pull request) ══  scope: ${scope.summary}`);
    const direct = hook.runDirectLegs(repoRoot, scope.direct);
    let gradleExit = 0;
    if (scope.gradle.length > 0) {
      gradleExit = await runUnderSlot({ layout: judged.where, label: GATE_OF_RECORD_LABEL, args: ["--no-build-cache", ...scope.gradle, "--continue", "--profile"], env: { JAVA_HOME: jdk.javaHome, ...judged.lockEnv } });
      if (cancelledBySignal(gradleExit)) return gradleExit;
    }
    const failed = await direct;
    const red = gradleExit !== 0 || failed.length > 0;
    console.log(red ? "GATE: FAIL" : "GATE: PASS");
    return red ? 1 : 0;
  } finally {
    judged.release();
  }
}

export async function run(argv: readonly string[]): Promise<number> {
  if (argv[0] === "--since" && argv.length === 2 && argv[1] !== undefined) return runSince(argv[1]);
  const javaHomeOnly = argv[0] === "--java-home-only" && argv.length === 1;
  if (argv.length > 0 && !javaHomeOnly) {
    console.error(`gate run: takes no arguments but --java-home-only (got ${argv.join(" ")}) — the gate of record is one fixed invocation`);
    return 2;
  }
  const jdk = resolveJdk21();
  if ("error" in jdk) {
    console.error(jdk.error);
    return 1;
  }
  if (javaHomeOnly) {
    console.log(jdk.javaHome);
    return 0;
  }
  console.error(`══ splice gate ══  (JAVA_HOME=${jdk.javaHome})`);
  const { repoRoot } = layout();
  const head = headAtStart(repoRoot);
  const judged = judgeIn(repoRoot, head);
  if ("error" in judged) {
    console.error(judged.error);
    return 1;
  }
  const { where, lockEnv } = judged;
  try {
    // THE RUN SENTINEL of the tree this verdict runs in, taken before the run that spans BOTH phases:
    // the slot lock is released between gradle and the release rehearsal, so it can never stand for
    // the verdict. Held by the kernel until this process ends by any means, SIGKILL included — which
    // matters because earlyoom is built to kill exactly this process under pressure. Keyed by the tree,
    // so a local gate in its own tree never makes a push to the pre-push tree refuse.
    const alreadyOpen = acquireRunSentinel(head, where.repoRoot);
    if (alreadyOpen) {
      console.error(`gate: refusing — a gate of record is already open over this tree (${describeOpenRun(alreadyOpen)})`);
      console.error("  two verdicts over one worktree cannot both be true; wait for it, or `bun tools/gate sentinel` to check.");
      return RUN_ALREADY_OPEN_EXIT;
    }
    const verdicts: [string, number][] = [];
    const slotExit = await runUnderSlot({ layout: where, label: GATE_OF_RECORD_LABEL, args: [...GATE_OF_RECORD_TASKS], env: { JAVA_HOME: jdk.javaHome, ...lockEnv } });
    if (cancelledBySignal(slotExit)) {
      console.error(`gate: cancelled — gradle gateOfRecord ended by signal (exit ${slotExit}); nothing runs after a cancellation`);
      return slotExit;
    }
    verdicts.push(["gradle gateOfRecord", slotExit]);
    for (const leg of AFTER_THE_SLOT) {
      console.error(`── ${leg.label} ──`);
      const proc = Bun.spawnSync([...leg.command], {
        cwd: where.repoRoot,
        env: { ...Bun.env, JAVA_HOME: jdk.javaHome, ...lockEnv },
        stdio: ["inherit", "inherit", "inherit"],
      });
      verdicts.push([leg.label, exitStatusOf(proc)]);
    }
    for (const [label, code] of verdicts) console.error(code === 0 ? `  ✓ ${label}` : `  ✗ ${label} (exit ${code})`);
    const red = verdicts.filter(([, code]) => code !== 0);
    console.log(red.length === 0 ? "GATE: PASS" : "GATE: FAIL");
    return red.length === 0 ? 0 : 1;
  } finally {
    judged.release();
  }
}
