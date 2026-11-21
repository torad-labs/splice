// Gradle runs locally by default. Set SPLICE_GRADLE_REMOTE=1 for a run you want rig to place elsewhere.
//
// `rig remote run`, given no machine, picks the first of the developer's machines with
// room, copies this work tree to it as it is on disk, runs the command there with its output here as it comes, and
// exits with the command's own status. The command is this same slot, under the same label and tasks, so the build
// there takes its own lock, resolves its own JDK 21 and sizes its own workers. Every `./gradlew` in the tree reaches
// the slot (the root wrapper re-executes through it), so the pre-push tier, `gate run`, an install's jar and a check
// that builds the jar all take this path. A task whose file is read here (the fat jar) names it with --fetch, and rig
// brings it back once the run there passes.
//
// A run goes to a machine of this one's system (`Linux/x86_64`, `Darwin/arm64`), so moving it never changes its
// verdict: the suite is written for, and judged on, the system it runs on. A whole-suite run that rig placed on a Mac
// failed ten Linux tests (ones that read /proc, or open a socket under a path longer than macOS allows). A run that
// only builds the fat jar goes to any machine, since the jar is the same whichever system builds it.
//
// Once rig is there, a run does not fall back to this machine. When rig started nothing because no machine had room,
// the copy there was busy or a machine did not answer, the slot asks again every RETRY_MS for as long as the slot
// waits for its lock (GRADLE_SLOT_WAIT_S); a connection lost mid-run is asked again the same way. Then, or at once
// when rig refused or could not copy the tree, the run fails with rig's 75 and says why: nothing ran here in its place.
//
// The run stays here when:
//   - SPLICE_GRADLE_REMOTE=1 was not requested;
//   - rig is not on PATH, or this is CI: nothing to send it with;
//   - SPLICE_GRADLE_HERE is set: the run that rig started on the other machine has it, so it builds where it stands,
//     and a person sets it for a run that needs this machine;
//   - the build root does not carry the gate (a test's throwaway project), since the run there is the gate's slot;
//   - a task makes a file read here that has no path to bring back (FETCHED names the ones that do);
//   - an argument names a path on this machine (`-p /tmp/…`, an init script): the other machine has no such path.
// A signal while it runs ends the run there through rig, and nothing then runs here.
import { closeSync, existsSync, mkdtempSync, openSync, readFileSync, rmSync } from "node:fs";
import { machine, tmpdir, type } from "node:os";
import { isAbsolute, join } from "node:path";
import { exitForSignal, exitStatusOf } from "./status.ts";

/** Set on a run that must build on the machine it is on: the run rig starts elsewhere, or one a person keeps here. */
export const BUILD_HERE = "SPLICE_GRADLE_HERE";
const BUILD_REMOTE = "SPLICE_GRADLE_REMOTE";

/** rig's status, and the slot's, for a run that was not started: EX_TEMPFAIL. */
export const NOT_STARTED_EXIT = 75;

/** A task that makes files read here, and where each lands from the build root, so rig can bring them back. */
const FETCHED: readonly { readonly task: RegExp; readonly paths: readonly string[] }[] = [
  { task: /^(:app:|:)?shadowJar$/, paths: ["app/build/libs/app-all.jar"] },
];

/** A task whose output the caller reads on this machine and that FETCHED has no path for. */
const MADE_HERE = /(^|:)(jar|installDist|assemble|build|dist\w*|link\w*|publish\w*)$/;

/** Why rig started nothing that another try may change: room, a busy copy, a machine that did not answer. */
const ASK_AGAIN = new Set(["no room", "no machine", "tree busy", "unreachable"]);

/** rig's status when the connection was lost once the command ran: the verdict there is unknown. */
const CONNECTION_LOST = 255;

const RETRY_MS = 30_000;

/** The descriptor rig writes its one status line to. */
const STATUS_FD = 3;

/** One gradle run the slot was asked for. */
export interface GradleRun {
  readonly buildRoot: string;
  readonly label: string;
  readonly args: readonly string[];
  readonly env: Readonly<Record<string, string | undefined>>;
}

/** rig's line once the run is over: `why` where it did not start the run. */
interface RigStatus {
  readonly exit?: number | null;
  readonly why?: string;
}

/** One try's end: the command's status there, or why nothing ran there and whether to ask again. */
type Try =
  | { readonly ran: number }
  | { readonly signal: NodeJS.Signals }
  | { readonly notRun: string; readonly again: boolean };

/** The status of the run when rig took it, or undefined when it is this machine's to run. */
export async function gradleElsewhere(run: GradleRun): Promise<number | undefined> {
  if (run.env[BUILD_REMOTE] !== "1" || run.env.CI || run.env[BUILD_HERE]) return undefined;
  const rig = Bun.which("rig", { PATH: run.env.PATH ?? "" });
  if (rig === null || !existsSync(join(run.buildRoot, "tools", "gate", "index.ts"))) return undefined;
  const fetched = run.args.flatMap((arg) => FETCHED.find((it) => it.task.test(arg))?.paths ?? []);
  const made = run.args.find((arg) => MADE_HERE.test(arg));
  const local = run.args.find((arg) => isAbsolute(arg.replace(/^--?[\w-]+=/, "")));
  const reason = made !== undefined ? `${made} makes a file this machine reads` : local && `${local} is a path here`;
  if (reason) {
    console.error(`gradle-slot: ${run.label} builds here: ${reason}`);
    return undefined;
  }
  const budgetMs = Number(run.env.GRADLE_SLOT_WAIT_S ?? "3600") * 1000;
  const since = Date.now();
  for (;;) {
    const ended = await oneTry(rig, run, [...new Set(fetched)]);
    if ("signal" in ended) return exitForSignal(ended.signal);
    if ("ran" in ended) return ended.ran;
    if (!ended.again || Date.now() - since + RETRY_MS > budgetMs) {
      console.error(`gradle-slot: ${run.label} did not run: ${ended.notRun}. Nothing ran on this machine in its place.`);
      return NOT_STARTED_EXIT;
    }
    console.error(`gradle-slot: ${run.label} waits for a machine (${ended.notRun}); asking again in ${RETRY_MS / 1000}s`);
    await Bun.sleep(RETRY_MS);
  }
}

/** One `rig remote run` of this slot, and what its status line says of it. */
async function oneTry(rig: string, run: GradleRun, fetched: readonly string[]): Promise<Try> {
  const folder = mkdtempSync(join(tmpdir(), "gate-elsewhere-"));
  const statusPath = join(folder, "status");
  const statusFd = openSync(statusPath, "w");
  try {
    const ended = await rigRun(rig, run, fetched, statusFd);
    if ("signal" in ended) return ended;
    const status = lastStatus(readFileSync(statusPath, "utf8"));
    if (status !== undefined && status.why === undefined) return { ran: ended.status };
    if (status?.why !== undefined) return { notRun: `rig: ${status.why}`, again: ASK_AGAIN.has(status.why) };
    if (ended.status === CONNECTION_LOST) return { notRun: "the connection to the machine was lost", again: true };
    return { notRun: `rig exited ${ended.status} and said nothing of a run (too old to pick a machine?)`, again: false };
  } finally {
    closeSync(statusFd);
    rmSync(folder, { recursive: true, force: true });
  }
}

/** rig's run of this slot on another machine: its status, or the signal this process got while it ran. */
async function rigRun(
  rig: string,
  run: GradleRun,
  fetched: readonly string[],
  statusFd: number,
): Promise<{ readonly status: number } | { readonly signal: NodeJS.Signals }> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(run.env)) if (typeof value === "string") env[key] = value;
  // rig gives the run there only the variables --env names: this one tells the slot there to build where it stands.
  env[BUILD_HERE] = "1";
  // uname's names, which are rig's: Linux/x86_64, Darwin/arm64
  const system = `${type()}/${machine()}`;
  const jarOnly = run.args.every((arg) => arg.startsWith("-") || FETCHED.some((it) => it.task.test(arg)));
  const argv = [
    rig, "remote", "run", "--dir", run.buildRoot, "--env", BUILD_HERE, "--status-fd", String(STATUS_FD),
    ...(jarOnly ? [] : ["--needs", `system=${system}`]),
    ...fetched.flatMap((path) => ["--fetch", path]),
    "--", "bun", "tools/gate", "slot", run.label, "--", ...run.args,
  ];
  console.error(`gradle-slot: ${run.label} goes to another ${jarOnly ? "" : `${system} `}machine through rig`);
  const child = Bun.spawn(argv, { cwd: run.buildRoot, env, stdio: ["inherit", "inherit", "inherit", statusFd] });
  let received: NodeJS.Signals | undefined;
  const signals: NodeJS.Signals[] = ["SIGINT", "SIGTERM", "SIGHUP"];
  const forward = (signal: NodeJS.Signals) => {
    received ??= signal;
    child.kill(signal);
  };
  for (const signal of signals) process.on(signal, forward);
  try {
    await child.exited;
  } finally {
    for (const signal of signals) process.off(signal, forward);
  }
  return received ? { signal: received } : { status: exitStatusOf(child) };
}

/** The last status line rig wrote, or undefined when it wrote none that reads. */
function lastStatus(text: string): RigStatus | undefined {
  const line = text.split("\n").filter((it) => it.trim() !== "").at(-1);
  if (line === undefined) return undefined;
  try {
    const parsed: unknown = JSON.parse(line);
    return typeof parsed === "object" && parsed !== null ? (parsed as RigStatus) : undefined;
  } catch {
    return undefined;
  }
}
