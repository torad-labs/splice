// Formatting + small helpers. Real numbers from the mgmt API only — these
// format, never invent.
import { create } from 'zustand';

export interface Resource<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
  lastUpdated: number | null;
}

export interface ResourceStore<T> {
  use: <U>(selector: (state: Resource<T>) => U) => U;
  get: () => Resource<T>;
  setData: (data: T) => void;
  setError: (message: string) => void;
  startLoading: () => void;
}

/** Domain-state cell for one mgmt endpoint: entity api segments write it,
 * views subscribe via selectors. Components never hold server state. */
export function createResource<T>(): ResourceStore<T> {
  const useStore = create<Resource<T>>(() => ({
    data: null,
    error: null,
    loading: false,
    lastUpdated: null,
  }));
  return {
    use: (selector) => useStore(selector),
    get: () => useStore.getState(),
    setData: (data) => useStore.setState({ data, error: null, loading: false, lastUpdated: Date.now() }),
    setError: (message) => useStore.setState({ error: message, loading: false }),
    startLoading: () => useStore.setState((s) => ({ ...s, loading: s.data === null })),
  };
}

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

export function fmtInt(n: number): string {
  return new Intl.NumberFormat('en-US').format(n);
}

export function fmtTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 10_000) return `${Math.round(n / 1000)}k`;
  if (n >= 1_000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

export function fmtDurationS(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s % 60}s`;
  return `${s}s`;
}

export function fmtMs(ms: number): string {
  if (ms >= 60_000) return fmtDurationS(ms / 1000);
  if (ms >= 1_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.round(ms)}ms`;
}

export function timeAgo(ts: number, now = Date.now()): string {
  const delta = Math.max(0, now - ts);
  if (delta < 5_000) return 'now';
  if (delta < 60_000) return `${Math.floor(delta / 1000)}s ago`;
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m ago`;
  return `${Math.floor(delta / 3_600_000)}h ago`;
}

/** Interval runner with immediate first tick; returns a stop function. */
export function poll(fn: () => void | Promise<void>, intervalMs: number): () => void {
  let stopped = false;
  const tick = () => {
    if (stopped) return;
    void fn();
  };
  tick();
  const id = setInterval(tick, intervalMs);
  return () => {
    stopped = true;
    clearInterval(id);
  };
}
