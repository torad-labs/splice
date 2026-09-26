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
import { describeOpenRun, probeRunSentinel, sentinelPath } from "../lib/sentinel.ts";

export const usage = "sentinel                             is a gate verdict open over this tree? (0 free, 1 open)";

export function sentinel(argv: readonly string[]): number {
  if (argv.length > 0) {
    console.error(`gate sentinel: takes no arguments (got ${argv.join(" ")})`);
    return 2;
  }
  const open = probeRunSentinel();
  if (!open) {
    console.log(`sentinel: FREE — no gate verdict is open over this tree (${sentinelPath()})`);
    return 0;
  }
  console.error(`sentinel: OPEN — a gate of record is running over this tree: ${describeOpenRun(open)}`);
  console.error("  its verdict covers the WORKTREE, so editing the tree now invalidates the run in progress.");
  return 1;
}
