// A gradle run on another of the developer's machines, when `rig` is on this machine's PATH and one has room for it.
//
// Nov 19, 2026: builds, checks and tests leave a developer's main machine for others of theirs, so it stays
// responsive while they run. `rig remote run`, given no machine, picks the first of the developer's machines with
// room, copies this work tree to it as it is on disk, runs the command there with its output here as it comes, and
// exits with the command's own status. The command is this same slot, under the same label and tasks, so the build
// there takes its own lock, resolves its own JDK 21 and sizes its own workers. Every `./gradlew` in the tree reaches
// the slot (the root wrapper re-executes through it), so the pre-push tier, `gate run` and a check that builds the
// jar all take this path.
//
// The run stays here when:
//   - rig is not on PATH, or this is CI: nothing to send it with;
//   - SPLICE_GRADLE_HERE is set: the run that rig started on the other machine has it, so it builds where it stands,
//     and a person sets it for a run that needs this machine;
//   - the build root does not carry the gate (a test's throwaway project), since the run there is the gate's slot;
//   - a task makes a file the caller reads here (the jar an install copies, a native binary, a distribution), since
//     rig brings no file back;
//   - an argument names a path on this machine (`-p /tmp/…`, an init script): the other machine has no such path;
//   - rig did not start it there: no machine had room, the copy there was busy, or rig refused the tree. rig's status
//     line says so, and a rig that wrote none (one too old to pick a machine itself) started nothing either;
//   - the connection was lost once it ran: no status line follows, the verdict there is unknown, and the build is
//     repeated here.
// A signal while it runs ends the run there through rig, and nothing then runs here.
import { closeSync, existsSync, mkdtempSync, openSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { isAbsolute, join } from "node:path";
import { exitForSignal, exitStatusOf } from "./status.ts";

/** Set on a run that must build on the machine it is on: the run rig starts elsewhere, or one a person keeps here. */
export const BUILD_HERE = "SPLICE_GRADLE_HERE";

/** A task whose output the caller reads on this machine. */
const MADE_HERE = /(^|:)(shadowJar|jar|installDist|assemble|build|dist\w*|link\w*|publish\w*)$/;

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

/** The status of the run when another machine ran it, or undefined when it is this machine's to run. */
export async function gradleElsewhere(run: GradleRun): Promise<number | undefined> {
  const rig = run.env.CI || run.env[BUILD_HERE] ? null : Bun.which("rig", { PATH: run.env.PATH ?? "" });
  if (rig === null || !existsSync(join(run.buildRoot, "tools", "gate", "index.ts"))) return undefined;
  const made = run.args.find((arg) => MADE_HERE.test(arg));
  const local = run.args.find((arg) => isAbsolute(arg.replace(/^--?[\w-]+=/, "")));
  const reason = made !== undefined ? `${made} makes a file this machine reads` : local && `${local} is a path here`;
  if (reason) {
    console.error(`gradle-slot: ${run.label} builds here: ${reason}`);
    return undefined;
  }
  const folder = mkdtempSync(join(tmpdir(), "gate-elsewhere-"));
  const statusPath = join(folder, "status");
  const statusFd = openSync(statusPath, "w");
  try {
    const ended = await rigRun(rig, run, statusFd);
    if ("signal" in ended) return exitForSignal(ended.signal);
    const status = lastStatus(readFileSync(statusPath, "utf8"));
    if (status !== undefined && status.why === undefined) return ended.status;
    const why = status?.why ?? `rig exited ${ended.status} with no word of a run`;
    console.error(`gradle-slot: ${run.label} builds here: no other machine ran it (${why})`);
    return undefined;
  } finally {
    closeSync(statusFd);
    rmSync(folder, { recursive: true, force: true });
  }
}

/** rig's run of this slot on another machine: its status, or the signal this process got while it ran. */
async function rigRun(
  rig: string,
  run: GradleRun,
  statusFd: number,
): Promise<{ readonly status: number } | { readonly signal: NodeJS.Signals }> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(run.env)) if (typeof value === "string") env[key] = value;
  // rig gives the run there only the variables --env names: this one tells the slot there to build where it stands.
  env[BUILD_HERE] = "1";
  const argv = [
    rig, "remote", "run", "--dir", run.buildRoot, "--env", BUILD_HERE, "--status-fd", String(STATUS_FD),
    "--", "bun", "tools/gate", "slot", run.label, "--", ...run.args,
  ];
  console.error(`gradle-slot: ${run.label} goes to another machine through rig`);
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
