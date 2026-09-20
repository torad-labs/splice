// `gate slot <label> -- <gradle args...>` — ONE gradle at a time per worktree.
import { layout } from "../lib/repo.ts";
import { NO_TASKS_EXIT, runUnderSlot } from "../lib/slot.ts";

export const usage = "slot <label> [--] <gradle args...>   run gradle under the worktree's gradle slot";

export function slot(argv: readonly string[]): number {
  const label = argv[0];
  if (!label || label.startsWith("-")) {
    console.error("gradle-slot: a label (row id or seat) is required — it is what a waiting seat reads out of the holder file");
    return NO_TASKS_EXIT;
  }
  // `--` is how the packet spells the boundary and how a shell keeps `--version` out of the
  // dispatcher; gradle-slot.sh takes the tasks positionally. Both spellings are accepted.
  const rest = argv[1] === "--" ? argv.slice(2) : argv.slice(1);
  return runUnderSlot({ layout: layout(), label, args: rest });
}
