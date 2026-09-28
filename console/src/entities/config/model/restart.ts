// Knobs that were SAVED and are not in force yet.
//
// FEATURES 2.2: three knobs hot-apply, every other one is snapshotted at Daemon.start, so a PATCH
// to one of them lands in the state file and does nothing until the daemon restarts. The console
// must not let that read as "applied": the surface contract (shape brief, Feedback) is that a
// restart-only knob COCKS the daemon strip until the restart runs.
//
// The list is driven by the daemon's own answer, never by a hand list: PATCH /api/config returns
// `restart_required`, and `markRestartPending` records exactly those keys. It is deliberately
// process-local and never persisted — it describes "this console has saved something the running
// daemon has not read yet", which is a fact about the session, not about the machine.
import { create } from 'zustand';

interface RestartState {
  /** Knob keys saved since the daemon last read the state file, sorted and deduped. */
  pending: string[];
  bootedAtEpochMillis: number | null;
  pendingAtEpochMillis: number | null;
  mark: (keys: readonly string[], bootedAtEpochMillis: number | null) => void;
  observe: (bootedAtEpochMillis: number) => void;
  clear: () => void;
}

const useStore = create<RestartState>((set) => ({
  pending: [],
  bootedAtEpochMillis: null,
  pendingAtEpochMillis: null,
  mark: (keys, bootedAtEpochMillis) => set((state) => ({
    pending: [...new Set([
      ...(state.pendingAtEpochMillis !== null && bootedAtEpochMillis !== null && state.pendingAtEpochMillis !== bootedAtEpochMillis
        ? [] : state.pending),
      ...keys,
    ])].sort(),
    pendingAtEpochMillis: bootedAtEpochMillis,
  })),
  observe: (bootedAtEpochMillis) => set((state) => {
    if (state.pendingAtEpochMillis !== null && state.pendingAtEpochMillis !== bootedAtEpochMillis) {
      return { pending: [], pendingAtEpochMillis: null, bootedAtEpochMillis };
    }
    return { bootedAtEpochMillis, pendingAtEpochMillis: state.pending.length > 0 ? state.pendingAtEpochMillis ?? bootedAtEpochMillis : null };
  }),
  clear: () => set({ pending: [], pendingAtEpochMillis: null }),
}));

/** The same `{ use, get, … }` shape every other store in the app exposes, so a page subscribes
 *  with a selector and nothing reaches for the raw zustand hook. */
export const restartStore = {
  use: <U>(selector: (state: RestartState) => U): U => useStore(selector),
  get: (): RestartState => useStore.getState(),
};

/** Record the keys under the boot the write reached. An unknown boot cannot prove a restart. */
export function markRestartPending(keys: readonly string[], bootedAtEpochMillis: number | null = useStore.getState().bootedAtEpochMillis): void {
  if (keys.length > 0) useStore.getState().mark(keys, bootedAtEpochMillis);
}

/** Only a measured replacement boot can clear settings this console saved. */
export function observeDaemonBoot(bootedAtEpochMillis: number): void {
  useStore.getState().observe(bootedAtEpochMillis);
}

/** Drop the list — what a restart does to it, and the only thing that honestly can. */
export function clearRestartPending(): void {
  useStore.getState().clear();
}
