// The gradle slot: ONE gradle at a time per repository (the checkout and every worktree of it),
// enforced by a lock instead of by convention.
//
// A port of checks/gradle-slot.sh, kept behaviourally identical to it; the lock moved from the
// worktree's build root to the git common dir with V4-341 (lockPath). Every line below that looks
// like a quirk is one:
//   - an EMPTY task list is DID NOT RUN, never PASSED (exit 2) — `./gradlew` with no task prints
//     BUILD SUCCESSFUL having compiled nothing (gradle-slot.sh:15-23);
//   - the holder file is written BEFORE any JVM starts; legacy runs remove it under the lock (:33-34);
//     joint runs write it only after host admission and trust it only while that host holds the lock;
//   - `buildgate` is this MACHINE's containment wrapper, absent on CI, so it is guarded (:37-42);
//   - `--offline` is a local nicety and a lie on CI, where the restored cache is always one
//     dependency bump behind the tree (:43-50);
//   - gradle's exit status propagates, and the release line reports it (:57-68);
//   - a signal reaches the WRAPPER, not only the process group: `kill <pid>` on the gate is how a
//     seat, a CI cancel and a supervisor all stop a run, and the shell script dies of it in
//     milliseconds. That is why gradle is orchestrated asynchronously below (:98-131).
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { takeExclusive } from "./flock.ts";
import type { Layout } from "./repo.ts";
import { exitForSignal, exitStatusOf } from "./status.ts";

export const SLOT_TIMEOUT_EXIT = 75;
export const NO_TASKS_EXIT = 2;
const FAST_PATH_MS = 1000;
/** The three the shell script traps. A forwarded signal is the only way the JVM ever hears one. */
const FORWARDED = ["SIGINT", "SIGTERM", "SIGHUP"] as const;

export interface SlotOptions {
  readonly layout: Layout;
  readonly label: string;
  readonly args: readonly string[];
  /** Overrides laid OVER the process environment — for the lock (GRADLE_SLOT_LOCK, GRADLE_SLOT_WAIT_S)
   *  and the child alike. Never a replacement: `gate run` passes only JAVA_HOME here, and the first
   *  cut read the lock settings from that one-key map, so an operator's GRADLE_SLOT_LOCK was
   *  discarded and the gate took the worktree's default lock beside a competing build (#170 review). */
  readonly env?: Record<string, string | undefined>;
  /** overridable so the tests can wait milliseconds instead of an hour */
  readonly pollMs?: number;
}

export function lockPath(layout: Layout, env: Record<string, string | undefined> = Bun.env): string {
  return env.GRADLE_SLOT_LOCK || sharedLock(layout) || `${layout.buildRoot}/.gradle-slot.lock`;
}

/**
 * V4-341: `<git common dir>/gradle-slot.lock`, one path for the checkout and every `git worktree add`
 * of it, so a seat's detached-worktree build queues behind the checkout's and every other worktree's.
 * Per worktree, on 2026-09-26 two seats' worktrees both held "the slot" at once, and the contention
 * pushed HeadServerLoadTest past its 30 s cap (34.2 s; 4.3 s alone). Resolved from the layout's own
 * root only: the caller's GIT_* variables (a hook sets them) and any repository above the root are
 * ignored. Undefined when git cannot say, and the caller falls back to the build root.
 */
function sharedLock(layout: Layout): string | undefined {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(Bun.env)) {
    if (typeof value === "string" && !key.startsWith("GIT_")) env[key] = value;
  }
  env.GIT_CEILING_DIRECTORIES = dirname(layout.repoRoot);
  const git = Bun.which("git", { PATH: env.PATH ?? "" });
  if (!git) return undefined;
  const common = Bun.spawnSync([git, "rev-parse", "--path-format=absolute", "--git-common-dir"], {
    cwd: layout.repoRoot,
    env,
    stdout: "pipe",
    stderr: "ignore",
  });
  const dir = common.exitCode === 0 ? common.stdout.toString().trim() : "";
  return dir ? join(dir, "gradle-slot.lock") : undefined;
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

/** A no-argument usage probe takes no admission or lock. Only the advertised token enables joint acquire. */
function supportsJoint(buildgate: string, buildRoot: string, env: Record<string, string | undefined>): boolean {
  const probe = Bun.spawnSync([buildgate], { cwd: buildRoot, env, stdin: "ignore", stdout: "ignore", stderr: "pipe" });
  const usage = probe.stderr.toString().split("\n").find((line) => line.startsWith("usage: buildgate "));
  return probe.exitCode === 2 && usage?.split(/\s+/).includes("[--joint]") === true;
}

/** Run gradle under the slot. Returns the exit code to propagate — it never calls process.exit. */
export async function runUnderSlot(options: SlotOptions): Promise<number> {
  const env: Record<string, string | undefined> = { ...Bun.env, ...(options.env ?? {}) };
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

  const buildgate = Bun.which("buildgate", { PATH: env.PATH ?? "" });
  const joint = buildgate !== null && supportsJoint(buildgate, options.layout.buildRoot, env);
  let slot: ReturnType<typeof takeExclusive>;
  if (!joint) {
    slot = takeExclusive(lock, FAST_PATH_MS, poll);
    if (!slot) {
      console.error(`gradle-slot: waiting (held by: ${holderOf(holderPath)})`);
      slot = takeExclusive(lock, waitSeconds * 1000, Math.max(poll, 250));
      if (!slot) {
        console.error(`gradle-slot: gave up after ${waitSeconds}s (held by: ${holderOf(holderPath)})`);
        return SLOT_TIMEOUT_EXIT;
      }
    }
    writeFileSync(holderPath, `${label} pid=${process.pid} since=${isoSeconds(new Date())}\n`);
  }
  const drop = () => {
    // The host owns the joint lock. Never erase another admitted build's holder after our host releases it.
    if (!slot) return;
    try {
      rmSync(holderPath, { force: true });
    } catch {
      /* the trap in gradle-slot.sh is best-effort too */
    }
  };
  process.once("exit", drop);

  // Why the child is orchestrated asynchronously: `Bun.spawnSync` blocks the thread, so a signal
  // that arrives while gradle runs is only DELIVERED once gradle has already finished — the handler
  // then runs after `finally` removed it, or not at all. Measured on the unfixed code: SIGTERM to
  // the gate's own pid left the wrapper alive, still holding the slot, and it exited 0 when the
  // child finished 1.5s later, where checks/gradle-slot.sh was dead in 12ms with 143.
  //
  // So: forward the signal to the JVM, keep the slot and the holder file until the child is
  // genuinely gone — a contender that took the lock while gradle was still shutting down would be
  // the second gradle in one project dir this file exists to prevent — and only then report
  // 128+signum. Handlers go on BEFORE the spawn so a signal landing between the holder write and
  // the first JVM instruction is not the default disposition killing us with the holder on disk.
  let child: Bun.Subprocess | undefined;
  let received: NodeJS.Signals | undefined;
  const handlers = FORWARDED.map(
    (signal) =>
      [
        signal,
        () => {
          received ??= signal;
          child?.kill(signal);
        },
      ] as const,
  );
  for (const [signal, forward] of handlers) process.on(signal, forward);

  try {
    if (joint) console.error(`gradle-slot: ${label} waits for buildgate admission`);
    else console.error(`gradle-slot: ${label} holds the slot — gradle busy`);
    child = spawnGradle(options.layout.buildRoot, args, env, buildgate, joint ? { lock, label } : undefined);
    if (received) child.kill(received);
    await child.exited;
    const rc = received ? exitForSignal(received) : exitStatusOf(child);
    console.error(`gradle-slot: ${label} released — gradle free (exit ${rc})`);
    return rc;
  } finally {
    for (const [signal, forward] of handlers) process.off(signal, forward);
    process.off("exit", drop);
    drop();
    slot?.release();
  }
}

function spawnGradle(
  buildRoot: string,
  args: readonly string[],
  env: Record<string, string | undefined>,
  buildgate: string | null,
  admission?: { readonly lock: string; readonly label: string },
): Bun.Subprocess {
  // The child inherits the caller's environment, as it does under bash — gradle needs JAVA_HOME,
  // HOME and PATH, and `buildgate` refuses without HOME. `env` is already that merge (runUnderSlot).
  const childEnv: Record<string, string> = {};
  for (const [key, value] of Object.entries(env)) {
    if (typeof value === "string") childEnv[key] = value;
  }
  // `-n "${CI:-}"` in the script: an empty CI is not CI.
  const offline = childEnv.CI ? [] : ["--offline"];
  // Projects build in parallel on CI only. The runner is the job's alone; on this box the slot's JVMs
  // run in buildgate.slice, which hostshield makes earlyoom's first victim, so here one project at a time.
  const parallel = childEnv.CI ? ["--parallel"] : [];
  // `command -v buildgate` — resolved against the child's PATH, because buildgate is this MACHINE's
  // memory-containment wrapper and nothing in the tree provides it. Calling it unconditionally is
  // what took every gradle leg on CI down with `buildgate: command not found` (gradle-slot.sh:37-42).
  const gradlew = `${buildRoot}/gradlew`;
  if (!existsSync(gradlew)) throw new Error(`gate: no gradle wrapper at ${gradlew}`);
  const gradleArgs = [...offline, ...parallel, "--no-daemon", ...args];
  let argv = buildgate ? [buildgate, gradlew, ...gradleArgs] : [gradlew, ...gradleArgs];
  if (buildgate && admission) {
    childEnv.BUILDGATE_LOCK = admission.lock;
    childEnv.SPLICE_GRADLE_REAL = gradlew;
    childEnv.SPLICE_GRADLE_LABEL = admission.label;
    argv = [buildgate, "--exclusive", "--joint", join(import.meta.dir, "../../bin/gradlew"), ...gradleArgs];
  }
  return Bun.spawn(argv, { cwd: buildRoot, stdio: ["inherit", "inherit", "inherit"], env: childEnv });
}
