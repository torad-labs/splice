// `gate run` — the gate of record.
//
// `gradle gateOfRecord` is the task graph (build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts:
// every module's check, the console's lint and tests, and one Exec task per row of
// tools/gate/config/ladder.json); this is the boundary, and there is exactly one. Three things
// here are correctness rather than habit (checks/gate.sh:14-50 and :69-84 until PR 5):
//   - JDK 21 is resolved BEFORE any JVM starts, from the running launcher's own `java.home`, and a
//     box without one is a refusal naming the remedy — never a gradle that picks whatever it finds;
//   - `clean` + `--no-build-cache`, because Kotlin's compile-avoidance ABI snapshot does not track
//     `internal` members: on 2026-09-16 :app:compileKotlin and :app:compileTestKotlin were restored
//     FROM-CACHE from different source states and SpinnerTest died with AbstractMethodError. A gate
//     of record must never measure a mixture of two source states;
//   - through the SLOT, because two gradles in one project dir clobber build/ outputs and hand each
//     other false reds, and a gate that a neighbour's timing can turn red is not a gate of record.
// `--no-daemon` is supplied by the slot itself, as it is for every other caller. `--continue` is
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
import { resolveJdk21 } from "../lib/jdk.ts";
import { layout } from "../lib/repo.ts";
import { runUnderSlot } from "../lib/slot.ts";
import { exitStatusOf } from "../lib/status.ts";

export const usage = "run [--java-home-only]                the gate of record: JDK 21, then clean gateOfRecord through the slot, then the release rehearsal";

export const GATE_OF_RECORD_LABEL = "gate-of-record";
export const GATE_OF_RECORD_TASKS = ["--no-build-cache", "clean", "gateOfRecord", "--continue"] as const;
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
  const { repoRoot } = layout();
  const verdicts: [string, number][] = [];
  const slotExit = await runUnderSlot({ layout: layout(), label: GATE_OF_RECORD_LABEL, args: [...GATE_OF_RECORD_TASKS], env: { JAVA_HOME: jdk.javaHome } });
  if (cancelledBySignal(slotExit)) {
    console.error(`gate: cancelled — gradle gateOfRecord ended by signal (exit ${slotExit}); nothing runs after a cancellation`);
    return slotExit;
  }
  verdicts.push(["gradle gateOfRecord", slotExit]);
  for (const leg of AFTER_THE_SLOT) {
    console.error(`── ${leg.label} ──`);
    const proc = Bun.spawnSync([...leg.command], {
      cwd: repoRoot,
      env: { ...Bun.env, JAVA_HOME: jdk.javaHome },
      stdio: ["inherit", "inherit", "inherit"],
    });
    verdicts.push([leg.label, exitStatusOf(proc)]);
  }
  for (const [label, code] of verdicts) console.error(code === 0 ? `  ✓ ${label}` : `  ✗ ${label} (exit ${code})`);
  const red = verdicts.filter(([, code]) => code !== 0);
  console.log(red.length === 0 ? "GATE: PASS" : "GATE: FAIL");
  return red.length === 0 ? 0 : 1;
}
