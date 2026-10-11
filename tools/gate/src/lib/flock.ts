// flock(2) through libc, so this process holds the SAME advisory lock the shell slot holds.
//
// checks/gradle-slot.sh takes the lock with util-linux `flock` on fd 9. An advisory lock is a
// property of the OPEN FILE DESCRIPTION on an inode, not of the tool that took it: a flock(2) here
// and a `flock(1)` there on the same path exclude each other. That is the whole reason this is
// flock(2) and not a pid file — the two entries must be unable to both hold the slot during the
// migration window in which both exist.
//
// The one behavioural difference from `flock -w N`: that spelling blocks in the kernel and wakes
// the instant the lock frees, this one retries LOCK_NB on a timer. Linux flock(2) gives waiters no
// ordering guarantee either way (all waiters are woken and race), so the difference is latency —
// bounded by the poll interval — never exclusion.
import { dlopen, FFIType } from "bun:ffi";
import { closeSync, openSync } from "node:fs";

const LOCK_EX = 2;
const LOCK_NB = 4;
const LOCK_UN = 8;

let cached: ((fd: number, op: number) => number) | undefined;

function libcFlock(): (fd: number, op: number) => number {
  if (cached) return cached;
  const candidates = ["libc.so.6", "libc.so", "libSystem.B.dylib"];
  const failures: string[] = [];
  for (const lib of candidates) {
    try {
      const handle = dlopen(lib, { flock: { args: [FFIType.i32, FFIType.i32], returns: FFIType.i32 } });
      cached = (fd, op) => handle.symbols.flock(fd, op);
      return cached;
    } catch (err) {
      failures.push(`${lib}: ${err instanceof Error ? err.message : String(err)}`);
    }
  }
  throw new Error(`gate: cannot load flock(2) from libc — ${failures.join("; ")}`);
}

export interface Slot {
  release(): void;
}

/**
 * Open `path` (creating it) and take the exclusive lock, retrying until `waitMs` elapses.
 * Returns undefined — never throws — when the wait runs out, so the caller can report the holder.
 */
export function takeExclusive(path: string, waitMs: number, pollMs = 50): Slot | undefined {
  const flock = libcFlock();
  const fd = openSync(path, "w");
  const deadline = Date.now() + waitMs;
  for (;;) {
    if (flock(fd, LOCK_EX | LOCK_NB) === 0) {
      let released = false;
      return {
        release() {
          if (released) return;
          released = true;
          flock(fd, LOCK_UN);
          closeSync(fd);
        },
      };
    }
    if (Date.now() >= deadline) {
      closeSync(fd);
      return undefined;
    }
    Bun.sleepSync(Math.min(pollMs, Math.max(1, deadline - Date.now())));
  }
}
