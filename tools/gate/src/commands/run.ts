// `gate run` — the gate of record.
//
// `./gradlew check` is the task graph; this is the boundary, and there is exactly one. Two things
// here are correctness rather than habit (checks/gate.sh:69-84):
//   - `clean` + `--no-build-cache`, because Kotlin's compile-avoidance ABI snapshot does not track
//     `internal` members: on 2026-09-16 :app:compileKotlin and :app:compileTestKotlin were restored
//     FROM-CACHE from different source states and SpinnerTest died with AbstractMethodError. A gate
//     of record must never measure a mixture of two source states;
//   - through the SLOT, because two gradles in one project dir clobber build/ outputs and hand each
//     other false reds, and a gate that a neighbour's timing can turn red is not a gate of record.
// `--no-daemon` is supplied by the slot itself, as it is for every other caller.
import { layout } from "../lib/repo.ts";
import { runUnderSlot } from "../lib/slot.ts";

export const usage = "run                                  the gate of record: clean check, through the slot";

export const GATE_OF_RECORD_LABEL = "gate-of-record";
export const GATE_OF_RECORD_TASKS = ["--no-build-cache", "clean", "check"] as const;

export function run(argv: readonly string[]): number {
  if (argv.length > 0) {
    console.error(`gate run: takes no arguments (got ${argv.join(" ")}) — the gate of record is one fixed invocation`);
    return 2;
  }
  return runUnderSlot({ layout: layout(), label: GATE_OF_RECORD_LABEL, args: [...GATE_OF_RECORD_TASKS] });
}
