// The status a SHELL would have reported for a finished child, from Bun's split form.
//
// `128 + signum` is not a convention borrowed from somewhere: it is the contract of the entries this
// CLI replaces. `npm run gate:rules` and checks/gradle-slot.sh run their children under bash, and
// bash reports a signalled child as 128+signum — 143 for SIGTERM, 130 for SIGINT. Bun reports the
// signal NAME in `signalCode` and leaves `exitCode` null, so `exitCode ?? 1` reads a killed child as
// a plain 1: indistinguishable from a rule violation or a failed build, and a caller that retries on
// 1 would restart work the operator just terminated. One helper, because both callers (astgrep.ts
// and slot.ts) must answer the same way and a second copy is a second answer.
import { constants as osConstants } from "node:os";

/** What a shell reports for a process killed by `signal`. */
export function exitForSignal(signal: NodeJS.Signals): number {
  return 128 + (osConstants.signals[signal as keyof typeof osConstants.signals] ?? 0);
}

/**
 * The status to propagate for a finished child — `Bun.spawn` and `Bun.spawnSync` alike, which is
 * why `signalCode` is widened AND optional: bun-types spells it `signalCode: NodeJS.Signals | null`
 * on `Subprocess` and `signalCode?: string` on `SyncSubprocess`.
 */
export function exitStatusOf(child: {
  readonly exitCode: number | null;
  readonly signalCode?: string | null;
}): number {
  return child.signalCode ? exitForSignal(child.signalCode as NodeJS.Signals) : (child.exitCode ?? 1);
}
