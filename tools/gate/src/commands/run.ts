// `gate run` — the gate of record.
//
// `./gradlew check` is the task graph; this is the boundary, and there is exactly one. Three things
// here are correctness rather than habit (checks/gate.sh:14-50 and :69-84 until PR 5):
//   - JDK 21 is resolved BEFORE any JVM starts, from the running launcher's own `java.home`, and a
//     box without one is a refusal naming the remedy — never a gradle that picks whatever it finds;
//   - `clean` + `--no-build-cache`, because Kotlin's compile-avoidance ABI snapshot does not track
//     `internal` members: on 2026-09-16 :app:compileKotlin and :app:compileTestKotlin were restored
//     FROM-CACHE from different source states and SpinnerTest died with AbstractMethodError. A gate
//     of record must never measure a mixture of two source states;
//   - through the SLOT, because two gradles in one project dir clobber build/ outputs and hand each
//     other false reds, and a gate that a neighbour's timing can turn red is not a gate of record.
// `--no-daemon` is supplied by the slot itself, as it is for every other caller.
//
// `--java-home-only` prints the resolved JAVA_HOME and stops: it is the resolver's own executable
// test (checks/oss/verify-OSS-J.sh runs it with JAVA_HOME unset and expects a Java 21 back).
import { resolveJdk21 } from "../lib/jdk.ts";
import { layout } from "../lib/repo.ts";
import { runUnderSlot } from "../lib/slot.ts";

export const usage = "run [--java-home-only]                the gate of record: JDK 21, then clean check through the slot";

export const GATE_OF_RECORD_LABEL = "gate-of-record";
export const GATE_OF_RECORD_TASKS = ["--no-build-cache", "clean", "check"] as const;

export async function run(argv: readonly string[]): Promise<number> {
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
  return runUnderSlot({
    layout: layout(),
    label: GATE_OF_RECORD_LABEL,
    args: [...GATE_OF_RECORD_TASKS],
    env: { JAVA_HOME: jdk.javaHome },
  });
}
