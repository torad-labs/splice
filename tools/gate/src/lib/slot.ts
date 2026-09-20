// The gradle slot: ONE gradle at a time per worktree, enforced by a lock instead of by convention.
//
// A port of checks/gradle-slot.sh, kept behaviourally identical on the same lock path so the two
// entries can never both hold the slot. Every line below that looks like a quirk is one:
//   - an EMPTY task list is DID NOT RUN, never PASSED (exit 2) — `./gradlew` with no task prints
//     BUILD SUCCESSFUL having compiled nothing (gradle-slot.sh:15-23);
//   - the holder file is written BEFORE any JVM starts and removed under a trap (:33-34);
//   - `buildgate` is this MACHINE's containment wrapper, absent on CI, so it is guarded (:37-42);
//   - `--offline` is a local nicety and a lie on CI, where the restored cache is always one
//     dependency bump behind the tree (:43-50);
//   - gradle's exit status propagates, and the release line reports it (:57-68).
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { constants as osConstants } from "node:os";
import { takeExclusive } from "./flock.ts";
import type { Layout } from "./repo.ts";

export const SLOT_TIMEOUT_EXIT = 75;
export const NO_TASKS_EXIT = 2;
const FAST_PATH_MS = 1000;

export interface SlotOptions {
  readonly layout: Layout;
  readonly label: string;
  readonly args: readonly string[];
  readonly env?: Record<string, string | undefined>;
  /** overridable so the tests can wait milliseconds instead of an hour */
  readonly pollMs?: number;
}

export function lockPath(layout: Layout, env: Record<string, string | undefined> = Bun.env): string {
  return env.GRADLE_SLOT_LOCK || `${layout.buildRoot}/.gradle-slot.lock`;
}

/** `date -Is`: seconds precision, local time, numeric offset — the format the holder line carries. */
export function isoSeconds(at: Date): string {
  const offsetMinutes = -at.getTimezoneOffset();
  const pad = (n: number) => String(Math.abs(Math.trunc(n))).padStart(2, "0");
  const sign = offsetMinutes >= 0 ? "+" : "-";
  return (
    `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}` +
    `T${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}` +
    `${sign}${pad(offsetMinutes / 60)}:${pad(offsetMinutes % 60)}`
  );
}

function holderOf(holderPath: string): string {
  try {
    return readFileSync(holderPath, "utf8").trim() || "unknown";
  } catch {
    return "unknown";
  }
}

/** Run gradle under the slot. Returns the exit code to propagate — it never calls process.exit. */
export function runUnderSlot(options: SlotOptions): number {
  const env = options.env ?? Bun.env;
  const { label, args } = options;

  if (args.length === 0) {
    console.error(
      `gradle-slot: ${label} asked for NO gradle tasks — refusing to report a pass for a run that does nothing.`,
    );
    console.error(`gradle-slot: name the tasks, e.g. bun tools/gate slot ${label} -- :app:test :app:detekt`);
    return NO_TASKS_EXIT;
  }

  const lock = lockPath(options.layout, env);
  const holderPath = `${lock}.holder`;
  const waitSeconds = Number(env.GRADLE_SLOT_WAIT_S ?? "3600");
  const poll = options.pollMs ?? 50;

  let slot = takeExclusive(lock, FAST_PATH_MS, poll);
  if (!slot) {
    console.error(`gradle-slot: waiting (held by: ${holderOf(holderPath)})`);
    slot = takeExclusive(lock, waitSeconds * 1000, Math.max(poll, 250));
    if (!slot) {
      console.error(`gradle-slot: gave up after ${waitSeconds}s (held by: ${holderOf(holderPath)})`);
      return SLOT_TIMEOUT_EXIT;
    }
  }

  writeFileSync(holderPath, `${label} pid=${process.pid} since=${isoSeconds(new Date())}\n`);
  const drop = () => {
    try {
      rmSync(holderPath, { force: true });
    } catch {
      /* the trap in gradle-slot.sh is best-effort too */
    }
  };
  const onSignal = (signal: NodeJS.Signals) => {
    drop();
    slot?.release();
    process.kill(process.pid, signal);
  };
  process.once("exit", drop);
  for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) process.once(signal, onSignal);

  try {
    console.error(`gradle-slot: ${label} holds the slot — gradle busy`);
    const rc = spawnGradle(options.layout.buildRoot, args, env);
    console.error(`gradle-slot: ${label} released — gradle free (exit ${rc})`);
    return rc;
  } finally {
    process.off("exit", drop);
    for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) process.off(signal, onSignal);
    drop();
    slot.release();
  }
}

function spawnGradle(buildRoot: string, args: readonly string[], env: Record<string, string | undefined>): number {
  // The child inherits the caller's environment, as it does under bash — gradle needs JAVA_HOME,
  // HOME and PATH, and `buildgate` refuses without HOME. `env` only ADDS to it.
  const childEnv: Record<string, string> = {};
  for (const [key, value] of Object.entries({ ...Bun.env, ...env })) {
    if (typeof value === "string") childEnv[key] = value;
  }
  // `-n "${CI:-}"` in the script: an empty CI is not CI.
  const offline = childEnv.CI ? [] : ["--offline"];
  // `command -v buildgate` — resolved against the child's PATH, because buildgate is this MACHINE's
  // memory-containment wrapper and nothing in the tree provides it. Calling it unconditionally is
  // what took every gradle leg on CI down with `buildgate: command not found` (gradle-slot.sh:37-42).
  const buildgate = Bun.which("buildgate", { PATH: childEnv.PATH ?? "" });
  const gradlew = `${buildRoot}/gradlew`;
  if (!existsSync(gradlew)) throw new Error(`gate: no gradle wrapper at ${gradlew}`);
  const argv = buildgate
    ? [buildgate, gradlew, ...offline, "--no-daemon", ...args]
    : [gradlew, ...offline, "--no-daemon", ...args];
  const proc = Bun.spawnSync(argv, { cwd: buildRoot, stdio: ["inherit", "inherit", "inherit"], env: childEnv });
  // bash reports a signalled child as 128+signum; Bun hands back the signal NAME instead.
  if (proc.signalCode) return 128 + (osConstants.signals[proc.signalCode as keyof typeof osConstants.signals] ?? 0);
  return proc.exitCode ?? 1;
}
