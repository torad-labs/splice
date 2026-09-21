// THE RUN SENTINEL — "is a verdict open over this tree?", answered by the kernel rather than by a
// heuristic.
//
// THE DEFECT IT ANSWERS. The gradle slot lock is taken and released PER LEG. Measured in
// gate-242dd749.log: TEN acquire/release pairs in one run — `gate-of-record` (l.2 -> l.950), seven
// more inside `:gateTests` (l.471-494, slot.test.ts's own fixtures, which print "gradle free
// (exit 0)" and once "(exit 7)"), then `release-verify` (l.958-959). So neither the lock nor the log
// answers whether a verdict is open over this worktree, and with four seats sharing one checkout
// that question got answered wrong twice in one day, in both directions: a live gate killed because
// the slot read FREE, and a verdict nearly reported from a run whose tree had been edited under it.
//
// LIVENESS IS AN FLOCK HELD FOR THE WHOLE RUN, never a staleness heuristic. A pid plus
// /proc/<pid>/stat field 22 (starttime, to defeat pid recycling) was the alternative, and it is
// strictly worse: a bash trap cannot catch SIGKILL, and SIGKILL is a DESIGNED-FOR case here —
// earlyoom is active system-wide and buildgate sets `choom -n 800` so builds volunteer to die first
// under pressure, which makes a 12-minute gradle run the intended victim. An flock makes staleness
// INEXPRESSIBLE instead of detectable: the kernel drops it on exit, SIGKILL, OOM-kill and reboot.
// Proven in that order, control first, so the FREEs are not vacuous:
//     no holder -> FREE     holder alive -> HELD     after kill -9 -> FREE
//
// ORDER IS LOAD-BEARING, AND NOT THE ORDER THIS WAS DESIGNED WITH. The design said: write the
// metadata atomically (temp + rename), THEN open and lock. That is wrong, and building it is what
// showed why — a rename onto the final path replaces the INODE, so a second run starting while a
// first holds the lock would orphan the holder's lock, acquire the new inode, and both would believe
// they held it. The one thing the sentinel exists to prevent. So: open the stable path, lock it,
// and only then write the metadata into the locked fd. No rename, ever.
//
// THE CONTENTS ARE FOR HUMANS. Liveness is the lock; the fields exist so a peer that finds the file
// HELD can say what it is waiting on. `head_at_start`, never `sha`: the verdict covers the WORKTREE,
// and the gate checks `git status` precisely because worktree and HEAD can differ, so a field named
// `sha` would invite the inference the gate exists to refuse.
//
// IT LIVES OUTSIDE THE WORKTREE. A sentinel inside the tree dirties it, and an empty `git status` is
// the gate of record's own precondition — a marker that fails the check it exists to protect.
// $XDG_RUNTIME_DIR is tmpfs and clears on reboot, which matches the lock's own lifetime. It gets
// splice's OWN directory rather than buildgate's: buildgate is hostshield-owned
// (hostshield MANIFEST.toml:38, `owner = true`), and borrowing another component's runtime directory
// couples this to a layout we do not control.
import { dlopen, FFIType, suffix } from "bun:ffi";
import { closeSync, mkdirSync, openSync, readFileSync, writeSync } from "node:fs";
import { dirname, join } from "node:path";

const LOCK_EX = 2;
const LOCK_NB = 4;

const { symbols: libc } = dlopen(`libc.${suffix}.6`, {
  flock: { args: [FFIType.i32, FFIType.i32], returns: FFIType.i32 },
});

export interface OpenRun {
  readonly headAtStart: string;
  readonly start: string;
  readonly pid: number;
}

/** `SPLICE_GATE_SENTINEL` exists so the tests can point at a scratch path; a test that locked the
 *  real one would block the machine's next gate for the length of the suite. */
export function sentinelPath(): string {
  const override = process.env.SPLICE_GATE_SENTINEL;
  if (override) return override;
  const runtime = process.env.XDG_RUNTIME_DIR ?? join(process.env.HOME ?? "/tmp", ".cache");
  return join(runtime, "splice", "gate-run.lock");
}

/** Held for the life of the process, deliberately never closed: releasing IS dying. */
let held: number | undefined;

/**
 * Take the sentinel. Returns null on success — the caller now holds it until the process ends by any
 * means. Returns the OPEN RUN when another gate already holds it, so the caller can name what it is
 * refusing to run beside.
 */
export function acquireRunSentinel(headAtStart: string): OpenRun | null {
  const path = sentinelPath();
  mkdirSync(dirname(path), { recursive: true });
  const fd = openSync(path, "a+");
  if (libc.flock(fd, LOCK_EX | LOCK_NB) !== 0) {
    const open = readOpenRun(path);
    closeSync(fd);
    return open ?? { headAtStart: "unknown", start: "unknown", pid: 0 };
  }
  writeSync(fd, `head_at_start=${headAtStart}\nstart=${new Date().toISOString()}\npid=${process.pid}\n`, 0);
  held = fd;
  return null;
}

/** What a peer asks. null means no verdict is open over this tree. */
export function probeRunSentinel(): OpenRun | null {
  const path = sentinelPath();
  mkdirSync(dirname(path), { recursive: true });
  const fd = openSync(path, "a+");
  const free = libc.flock(fd, LOCK_EX | LOCK_NB) === 0;
  const open = free ? null : readOpenRun(path);
  closeSync(fd); // closing releases the probe's own lock; the holder's is untouched
  return open;
}

function readOpenRun(path: string): OpenRun | null {
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch {
    return null;
  }
  const field = (name: string): string => text.match(new RegExp(`^${name}=(.*)$`, "m"))?.[1]?.trim() ?? "";
  const pid = Number.parseInt(field("pid"), 10);
  return { headAtStart: field("head_at_start"), start: field("start"), pid: Number.isNaN(pid) ? 0 : pid };
}

export function describeOpenRun(open: OpenRun): string {
  return `pid ${open.pid}, started ${open.start}, HEAD at start ${open.headAtStart}`;
}

/** Test seam only: drop the lock inside one process so an arm can prove both states. */
export function releaseForTests(): void {
  if (held !== undefined) closeSync(held);
  held = undefined;
}
