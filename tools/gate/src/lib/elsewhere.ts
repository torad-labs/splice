// The installed runners snapshot the worktree, serialize remote runs, and exit with the command's status.
import { createWriteStream, existsSync, mkdirSync, mkdtempSync, renameSync, rmSync, statSync } from "node:fs";
import { machine, type } from "node:os";
import { dirname, isAbsolute, join } from "node:path";
import { pipeline } from "node:stream/promises";
import { exitForSignal, exitStatusOf } from "./status.ts";

export const BUILD_HERE = "SPLICE_GRADLE_HERE";
const NOT_STARTED = 75;
const FORWARDED_SIGNALS = ["SIGINT", "SIGTERM", "SIGHUP"] as const;
const SHADOW_JAR = /^(:app:|:)?shadowJar$/;
const APP_JAR = "app/build/libs/app-all.jar";
const ALL_PLATFORMS = "-Psplice.allPlatforms";
const MADE_HERE = /(^|:)(jar|installDist|assemble|build|dist\w*|link\w*|publish\w*)$/;
const SYSTEM = `${type()} ${machine()}`;
const SYSTEM_RUNNER = type() === "Darwin" ? "on-mac" : "on-box";
const OTHER_RUNNER = SYSTEM_RUNNER === "on-box" ? "on-mac" : "on-box";

export interface GradleRun {
  readonly buildRoot: string;
  readonly label: string;
  readonly args: readonly string[];
  readonly env: Readonly<Record<string, string | undefined>>;
}

type Location = Pick<GradleRun, "buildRoot" | "env">;
interface RunOutput {
  readonly cancel?: AbortSignal | undefined;
  readonly log?: (text: string) => void;
}

function runnersFor({ buildRoot, env }: Location) {
  if (env.CI || env[BUILD_HERE] || !existsSync(join(buildRoot, "tools", "gate", "index.ts"))) return undefined;
  const path = { PATH: env.PATH ?? "" };
  const matching = Bun.which(SYSTEM_RUNNER, path);
  const other = Bun.which(OTHER_RUNNER, path);
  return matching || other ? { matching, other } : undefined;
}

export function keptOffThisMachine(run: Location): boolean {
  return runnersFor(run) !== undefined;
}

/** Only a known missing runner is unrun. A runner's nonzero status might be a failed check, not unavailability. */
export function noMachineFor(run: Location): string | undefined {
  return runnersFor(run)?.matching === null ? `no ${SYSTEM_RUNNER} on PATH for ${SYSTEM}` : undefined;
}

// Check the actual host before judging anything; ON_MAC_HOST can redirect either runner. Keep jar bytes on
// stdout and build logs on stderr so the same locked checkout makes and returns the artifact. EOF on the
// runner's input cancels the slot without relying on SSH forwarding a signal.
const REMOTE_SLOT = `
system=$1 artifact=$2
shift 2
if [ -n "$system" ]; then
  actual=$(uname -sm) || exit
  if [ "$actual" != "$system" ]; then
    printf 'gradle-slot: expected %s, got %s; Gradle did not run.\\n' "$system" "$actual" >&2
    exit 75
  fi
fi
exec 3<&0
if [ -n "$artifact" ]; then
  "$@" >&2 &
else
  "$@" &
fi
slot=$!
( IFS= read -r _ || kill -TERM "$slot" 2>/dev/null ) <&3 &
watcher=$!
wait "$slot"
status=$?
kill "$watcher" 2>/dev/null
[ "$status" -eq 0 ] || exit "$status"
if [ -n "$artifact" ]; then
  exec cat "$artifact"
fi
`;

/** Undefined means this is a local run by policy, not that an attempted off-host run failed. */
export async function gradleElsewhere(run: GradleRun, output: RunOutput = {}): Promise<number | undefined> {
  const runners = runnersFor(run);
  if (runners === undefined) return undefined;
  const local = run.args.find((arg) => MADE_HERE.test(arg) || isAbsolute(arg.replace(/^--?[\w-]+=/, "")));
  if (local !== undefined) {
    console.error(`gradle-slot: ${run.label}: ${local} requires this machine; set ${BUILD_HERE}=1 explicitly to run it here.`);
    return NOT_STARTED;
  }

  const artifact = run.args.some((arg) => SHADOW_JAR.test(arg)) ? APP_JAR : undefined;
  const jarOnly = artifact !== undefined && run.args.every((arg) => arg.startsWith("-") || SHADOW_JAR.test(arg));
  const platforms = run.args.find((arg) => arg.startsWith(ALL_PLATFORMS));
  const portable = jarOnly && (platforms === undefined || platforms === `${ALL_PLATFORMS}=true`);
  // A portable jar can use the Mac without occupying the Linux test runner.
  const runner = portable && SYSTEM_RUNNER === "on-box" ? runners.other ?? runners.matching : runners.matching ?? (portable ? runners.other : null);
  if (runner === null) {
    console.error(`gradle-slot: ${run.label} did not run: no ${SYSTEM_RUNNER} on PATH for ${SYSTEM}. Nothing ran here in its place.`);
    return NOT_STARTED;
  }
  if (output.cancel?.aborted) return exitForSignal("SIGTERM");
  const args = portable && platforms === undefined ? [...run.args, `${ALL_PLATFORMS}=true`] : run.args;
  console.error(`gradle-slot: ${run.label} goes through ${runner.split("/").at(-1)}${portable ? "" : ` (${SYSTEM})`}`);
  const argv = [
    runner, "sh", "-c", REMOTE_SLOT, "splice-gradle", portable ? "" : SYSTEM, artifact ?? "",
    "env", `${BUILD_HERE}=1`, "bun", "tools/gate", "slot", run.label, "--", ...args,
  ];
  return runRemote(run, argv, output, artifact === undefined ? undefined : join(run.buildRoot, artifact));
}

async function pump(stream: ReadableStream<Uint8Array> | undefined, log: RunOutput["log"]): Promise<void> {
  if (!stream || log === undefined) return;
  const decoder = new TextDecoder();
  for await (const chunk of stream) log(decoder.decode(chunk, { stream: true }));
  log(decoder.decode());
}

async function runRemote(run: GradleRun, argv: string[], output: RunOutput, target?: string): Promise<number> {
  if (target !== undefined) mkdirSync(dirname(target), { recursive: true });
  const temp = target === undefined ? undefined : mkdtempSync(join(dirname(target), ".splice-jar-"));
  const file = temp === undefined ? undefined : join(temp, "app-all.jar");
  try {
    const child = Bun.spawn(argv, {
      cwd: run.buildRoot,
      env: run.env,
      stdin: "pipe",
      stdout: file !== undefined || output.log !== undefined ? "pipe" : "inherit",
      stderr: output.log !== undefined ? "pipe" : "inherit",
    });
    let interrupted: NodeJS.Signals | undefined;
    const stop = (signal: NodeJS.Signals): void => {
      if (interrupted !== undefined) return;
      interrupted = signal;
      child.stdin.end();
      child.kill(signal);
    };
    const abort = () => stop("SIGTERM");
    for (const signal of FORWARDED_SIGNALS) process.on(signal, stop);
    output.cancel?.addEventListener("abort", abort, { once: true });
    try {
      const artifact = file !== undefined && child.stdout
        ? pipeline(child.stdout, createWriteStream(file)).then(() => undefined, (error: unknown) => {
          child.kill("SIGTERM");
          return error;
        })
        : pump(child.stdout, output.log);
      const [, writeError] = await Promise.all([child.exited, artifact, pump(child.stderr, output.log)]);
      if (interrupted !== undefined) return exitForSignal(interrupted);
      if (writeError !== undefined) throw writeError;
      const status = exitStatusOf(child);
      if (status === 0 && file !== undefined && target !== undefined) {
        if (statSync(file).size === 0) throw new Error("runner returned an empty jar");
        renameSync(file, target);
      }
      return status;
    } finally {
      output.cancel?.removeEventListener("abort", abort);
      for (const signal of FORWARDED_SIGNALS) process.off(signal, stop);
    }
  } catch (error) {
    console.error(`gradle-slot: ${run.label} could not complete off-host: ${error instanceof Error ? error.message : error}. Nothing ran here in its place.`);
    return NOT_STARTED;
  } finally {
    if (temp !== undefined) rmSync(temp, { recursive: true, force: true });
  }
}
