// The settings this console saved that the daemon applies only after a restart, held for the page session: a fact about
// the session, never persisted (types/config.ts RestartState). The transitions are lib/config.ts's pure reducers; this is
// the store that holds their state and subscribes a page. Only a measured replacement boot clears it (`observeBoot`).
import { useSyncExternalStore } from 'react';
import { NO_RESTART_PENDING, markRestartPending, observeDaemonBoot } from './config';
import type { RestartState } from '../types/config';

let state: RestartState = NO_RESTART_PENDING;
const listeners = new Set<() => void>();

function set(next: RestartState): void {
  if (next === state) return;
  state = next;
  listeners.forEach((fn) => fn());
}

/** A write the daemon answered with `restart_required` keys: they wait under the boot the write reached. */
export const recordSaved = (keys: readonly string[], boot: number | null): void => set(markRestartPending(state, keys, boot));

/** The boot /health names now. A different one from what the keys wait under is the restart. */
export const observeBoot = (boot: number): void => {
  const next = observeDaemonBoot(state, boot);
  if (next.pending.length !== state.pending.length || next.bootedAtEpochMillis !== state.bootedAtEpochMillis || next.pendingAtEpochMillis !== state.pendingAtEpochMillis) set(next);
};

/** For tests: forget everything. */
export const resetRestartPending = (): void => set(NO_RESTART_PENDING);

/** The keys waiting now. */
export const pendingNow = (): readonly string[] => state.pending;

export function useRestartPending(): readonly string[] {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => {
        listeners.delete(fn);
      };
    },
    pendingNow,
  );
}
