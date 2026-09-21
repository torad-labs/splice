// The live connection: ONE streaming fetch of /api/events, reopened with backoff, its frames
// parsed and handed to whoever subscribed.
//
// WHY fetch AND NOT EventSource: the route is bearer-guarded, and the browser's EventSource API
// cannot send a header. A streaming fetch can, and this is the api segment of an entity, which is
// the one place in the app allowed to call fetch at all (the ast-grep wall `webui-fetch-only-in-api`
// names entities/*/api as the exemption).
//
// WHAT IT DOES NOT DO: it subscribes nothing. Entities that refetch on an event do that in their
// own rows (M3-01); this row builds the transport and proves it with hand-written frames, because
// the daemon route (V4-126) is still in flight.
import { getStoredKey, noteUnauthorized } from '@shared/api';
import { backoffMs, parseFrames } from '@shared/lib/live';
import type { EventFrame, EventKind } from '@shared/lib/live';
import { eventsStore } from '../model/store';

const STREAM_PATH = '/api/events';

type FrameListener = (frame: EventFrame) => void;

/** One set of listeners per kind, plus the general ones: an entity asks for the kinds that change
 *  its data, and the general list is for a surface that watches the stream itself. */
const byKind = new Map<string, Set<FrameListener>>();
const anyKind = new Set<FrameListener>();

/**
 * Watch frames of one kind. The return value is the unsubscribe, per CONTRACTS.md section 6.
 *
 * A kind this build does not know is never delivered to a typed subscriber, but it is still
 * parsed and still counts as a frame: the stream's health must not depend on which kinds this
 * build happens to understand.
 */
export function subscribe(kind: EventKind, fn: FrameListener): () => void {
  const set = byKind.get(kind) ?? new Set<FrameListener>();
  set.add(fn);
  byKind.set(kind, set);
  return () => {
    set.delete(fn);
  };
}

/** Every frame, whatever its kind. */
export function subscribeAll(fn: FrameListener): () => void {
  anyKind.add(fn);
  return () => {
    anyKind.delete(fn);
  };
}

function dispatch(frame: EventFrame): void {
  for (const fn of byKind.get(frame.kind) ?? []) fn(frame);
  for (const fn of anyKind) fn(frame);
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

let controller: AbortController | null = null;
let running = false;
let attempt = 0;

/** Read the stream until it ends or the connection is aborted, dispatching every frame. */
async function read(body: ReadableStream<Uint8Array>): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    const now = Date.now();
    // Any byte is evidence the link is alive; only a frame is evidence something happened.
    eventsStore.set({ lastBeatAt: now });
    const parsed = parseFrames(buffer, decoder.decode(value, { stream: true }));
    buffer = parsed.rest;
    if (parsed.dropped > 0) eventsStore.set({ dropped: eventsStore.get().dropped + parsed.dropped });
    for (const frame of parsed.frames) {
      attempt = 0; // a frame means the link works: the next reopen starts at 1s again
      eventsStore.set({
        lastFrameAt: now,
        lastId: frame.id ?? eventsStore.get().lastId,
      });
      dispatch(frame);
    }
  }
}

async function loop(): Promise<void> {
  while (running) {
    const key = getStoredKey();
    if (key === '') {
      // No key yet: there is nothing to authenticate with, and this is not a failure to retry.
      // The key gate re-arms the app, and a caller connects again after the operator pastes one.
      running = false;
      eventsStore.set({ status: 'off' });
      return;
    }

    controller = new AbortController();
    try {
      const headers: Record<string, string> = {
        Authorization: `Bearer ${key}`,
        Accept: 'text/event-stream',
      };
      const lastId = eventsStore.get().lastId;
      if (lastId !== null) headers['Last-Event-ID'] = String(lastId);

      const res = await fetch(STREAM_PATH, { headers, signal: controller.signal });

      if (res.status === 401) {
        // The management key is stale. The lock and the listener the shell's gate reacts to both
        // live in the shared client, so the stream hands it the 401 rather than arming a second
        // lock of its own: a lock the key gate did not own is one the gate could not clear.
        noteUnauthorized();
        running = false;
        eventsStore.set({ status: 'off' });
        return;
      }
      if (!res.ok || res.body === null) throw new Error(`HTTP ${res.status}`);

      eventsStore.set({ status: 'live' });
      await read(res.body);
    } catch {
      // A read that failed (the daemon restarted, the network dropped, the connection was aborted)
      // is not an error to report: the loop below is what handles it.
      if (!running) return;
    }

    if (!running) return;
    eventsStore.set({ status: 'reconnecting' });
    await sleep(backoffMs(attempt));
    attempt += 1;
  }
}

/**
 * Open the connection, once. A second call while one is open does nothing: the app, the rule and a
 * future page may all ask for it, and there is exactly one stream.
 */
export function connect(): void {
  if (running) return;
  running = true;
  attempt = 0;
  eventsStore.set({ status: 'reconnecting' });
  void loop();
}

/** Stop the connection and un-arm the loop. The console never calls this (a single-file app lives
 *  until its tab does); it exists so a test can end one stream before starting the next. */
export function disconnect(): void {
  running = false;
  controller?.abort();
  controller = null;
  eventsStore.set({ status: 'off' });
}
