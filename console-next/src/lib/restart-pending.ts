// The settings this console saved that the daemon applies only after a restart: the console's own record of its
// own writes (the daemon has no field for it), kept in the browser so a reload does not forget them. Needs you
// prints them as the daemon's one item; a restart clears them.
import { useSyncExternalStore } from 'react';
import { readJson, writeJson } from './storage';

const KEY = 'splice-restart-pending';
const listeners = new Set<() => void>();
let held: readonly string[] | null = null;

function load(): readonly string[] {
  if (held !== null) return held;
  const raw = readJson<unknown>(KEY, []);
  held = Array.isArray(raw) ? raw.filter((key): key is string => typeof key === 'string') : [];
  return held;
}

function store(next: readonly string[]): void {
  held = next;
  writeJson(KEY, next);
  listeners.forEach((fn) => fn());
}

/** A saved setting waits for a restart. Saving the same one again keeps one entry. */
export function markRestartPending(setting: string): void {
  if (!load().includes(setting)) store([...load(), setting]);
}

/** The daemon restarted: nothing waits any more. */
export function clearRestartPending(): void {
  if (load().length > 0) store([]);
}

export function useRestartPending(): readonly string[] {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => {
        listeners.delete(fn);
      };
    },
    load,
  );
}
