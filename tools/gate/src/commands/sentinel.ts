// `gate sentinel` — the question four seats on one checkout kept answering wrong: is a verdict open
// over this tree right now?
//
// Before this verb the only things to look at were the gradle slot and the gate log, and neither
// answers it. The slot is taken and released PER LEG (ten acquire/release pairs in one measured
// run), so "gradle free" means the current leg finished, not that the gate did. Reading it as the
// latter is how a live gate got killed on 2026-09-21, and how a verdict was nearly reported from a
// run whose worktree had been edited underneath it.
//
// THE SHELL CONTRACT IS THE POINT: exit 0 means free, exit 1 means a verdict is open. So a peer
// writes `bun tools/gate sentinel && <edit the tree>` and cannot get it wrong, which is the whole
// reason this is a verb and not a file for people to stat.
//
// The exit code answers for THIS tree, the one a seat edits. A local gate and pre-push each judge committed bytes in a
// build tree of their own (prepush-tree.ts), so editing this tree never invalidates them; their verdicts are listed too,
// so a seat can see what is running.
import { spawnSync } from "node:child_process";
import { join } from "node:path";
import { GATE_TREE, PRE_PUSH_TREE } from "../lib/prepush-tree.ts";
import { layout } from "../lib/repo.ts";
import { describeOpenRun, probeRunSentinel, sentinelPath } from "../lib/sentinel.ts";

export const usage = "sentinel                             is a gate verdict open over this tree? (0 free, 1 open)";

/** The build trees under the git common dir, or none when git cannot say. */
function buildTrees(repoRoot: string): { readonly label: string; readonly path: string }[] {
  const common = spawnSync("git", ["rev-parse", "--path-format=absolute", "--git-common-dir"], { cwd: repoRoot });
  const dir = common.status === 0 ? common.stdout.toString().trim() : "";
  if (!dir) return [];
  return [
    { label: "the local gate's build tree", path: join(dir, "splice-prepush", GATE_TREE) },
    { label: "the pre-push build tree", path: join(dir, "splice-prepush", PRE_PUSH_TREE) },
  ];
}

export function sentinel(argv: readonly string[]): number {
  if (argv.length > 0) {
    console.error(`gate sentinel: takes no arguments (got ${argv.join(" ")})`);
    return 2;
  }
  const { repoRoot } = layout();
  for (const tree of buildTrees(repoRoot)) {
    const running = probeRunSentinel(tree.path);
    console.log(running ? `sentinel: a verdict is running in ${tree.label}: ${describeOpenRun(running)}` : `sentinel: ${tree.label} is idle`);
  }
  const open = probeRunSentinel(repoRoot);
  if (!open) {
    console.log(`sentinel: FREE — no gate verdict is open over this tree (${sentinelPath(repoRoot)})`);
    return 0;
  }
  console.error(`sentinel: OPEN — a gate of record is running over this tree: ${describeOpenRun(open)}`);
  console.error("  its verdict covers the WORKTREE, so editing the tree now invalidates the run in progress.");
  return 1;
}
