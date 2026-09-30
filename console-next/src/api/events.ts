// The live connection: ONE streaming fetch of /api/events, reopened with backoff. Each frame marks the
// queries it changes stale, so a page follows the daemon without polling every card.
import type { QueryClient } from '@tanstack/react-query';
import { backoffMs, parseFrames } from '../lib/live';
import { currentKey, openStream } from './client';
import { keys } from './queries';

/** What a frame kind changes. An unknown kind still counts as a frame and changes nothing. */
const STALE_BY_KIND: Record<string, readonly (readonly string[])[]> = {
  'head.state': [keys.heads, keys.usage, keys.auth],
  'turn.start': [keys.liveTurns, keys.sessions],
  'turn.end': [keys.liveTurns, keys.sessions, keys.usage, keys.perf],
  'session.change': [keys.sessions],
  'message.edge': [keys.edges],
  'account.switch': [keys.accounts, keys.auth, keys.heads, keys.usage],
};

export type StreamStatus = 'off' | 'reconnecting' | 'live';

let status: StreamStatus = 'off';
const listeners = new Set<() => void>();
const setStatus = (next: StreamStatus): void => {
  status = next;
  listeners.forEach((fn) => fn());
};
export const streamStatus = (): StreamStatus => status;
export function subscribeStatus(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

/** Open the stream once and keep it open until `signal` aborts. A drop that reopens marks everything
 *  stale: what happened while it was down was never delivered. */
export async function followEvents(client: QueryClient, signal: AbortSignal): Promise<void> {
  let lastId: number | null = null;
  let attempt = 0;
  let opens = 0;
  while (!signal.aborted) {
    if (currentKey() === '') {
      setStatus('off');
      return;
    }
    try {
      const res = await openStream(signal, lastId);
      if (res.status === 401) {
        setStatus('off');
        return;
      }
      if (!res.ok || res.body === null) throw new Error(`HTTP ${res.status}`);
      setStatus('live');
      opens += 1;
      if (opens > 1) void client.invalidateQueries();
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        const parsed = parseFrames(buffer, decoder.decode(value, { stream: true }));
        buffer = parsed.rest;
        for (const frame of parsed.frames) {
          attempt = 0;
          lastId = frame.id ?? lastId;
          for (const key of STALE_BY_KIND[frame.kind] ?? []) void client.invalidateQueries({ queryKey: [...key] });
        }
      }
    } catch {
      if (signal.aborted) return;
    }
    if (signal.aborted) return;
    setStatus('reconnecting');
    await sleep(backoffMs(attempt));
    attempt += 1;
  }
}
