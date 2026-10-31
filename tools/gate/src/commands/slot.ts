// `gate slot <label> -- <gradle args...>` — ONE gradle at a time per worktree.
import { resolveJdk21 } from "../lib/jdk.ts";
import { layout } from "../lib/repo.ts";
import { NO_TASKS_EXIT, runUnderSlot } from "../lib/slot.ts";

export const usage = "slot <label> [--] <gradle args...>   run gradle under the worktree's gradle slot";

export async function slot(argv: readonly string[]): Promise<number> {
  const label = argv[0];
  if (!label || label.startsWith("-")) {
    console.error("gradle-slot: a label (row id or seat) is required — it is what a waiting seat reads out of the holder file");
    return NO_TASKS_EXIT;
  }
  // `--` is how the packet spells the boundary and how a shell keeps `--version` out of the
  // dispatcher; gradle-slot.sh takes the tasks positionally. Both spellings are accepted.
  const rest = argv[1] === "--" ? argv.slice(2) : argv.slice(1);
  // Gradle builds on the JDK the hooks and `gate run` resolve, not on whatever JAVA_HOME reached this process: a
  // version manager's bun shim (mise) exports its own default java's home, and the daemon then starts on that one.
  // No JDK 21 found leaves the environment as it came, and gradle names the missing toolchain itself.
  const jdk = resolveJdk21();
  const env = "javaHome" in jdk ? { JAVA_HOME: jdk.javaHome } : {};
  return runUnderSlot({ layout: layout(), label, args: rest, env });
}
