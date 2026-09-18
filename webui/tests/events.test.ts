// M3-00: the live connection. The daemon route (V4-126) is in flight, so the frame contract is
// proven exactly the way the row says: hand-written frames through the real parser, and a stubbed
// fetch standing in for the stream, including the two things a live stream does that a naive
// parser gets wrong - a frame split across two chunks, and a heartbeat that is not a frame.
//
// The transport is a streaming fetch, not EventSource (which cannot send the bearer header), so
// what the tests check about the request is what the daemon will actually receive: the
// Authorization header, and the Last-Event-ID that makes a reconnect resume instead of restarting.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { backoffMs, parseFrames } from '../src/shared/lib/live';
import type { EventFrame } from '../src/shared/lib/live';
import { connect, disconnect, subscribe, subscribeAll } from '../src/entities/events';
import { eventsStore } from '../src/entities/events/model/store';

const frame = (id: number, kind: string, data: unknown): string =>
  `id: ${id}\nevent: ${kind}\ndata: ${JSON.stringify(data)}\n\n`;

const encoder = new TextEncoder();

/** A stream that delivers the given chunks and then ends, the way a daemon that stopped does. */
function closedStream(chunks: readonly string[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
}

/** A stream that stays open and can be pushed into, the way a live one behaves. */
function openStream(): { body: ReadableStream<Uint8Array>; push: (chunk: string) => void } {
  let push: (chunk: string) => void = () => undefined;
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      push = (chunk: string) => controller.enqueue(encoder.encode(chunk));
    },
  });
  return { body, push: (chunk: string) => push(chunk) };
}

/** The stream's own key check is the shared client's: with no localStorage (node) there is no key,
 *  so the tests supply one exactly as the operator's browser does. */
beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: () => 'test-key', setItem: () => undefined });
  eventsStore.set({ status: 'off', lastFrameAt: null, lastBeatAt: null, lastId: null, dropped: 0 });
});

afterEach(() => {
  disconnect();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// ── the parser ───────────────────────────────────────────────────────────────

describe('parseFrames', () => {
  test('reads a whole frame', () => {
    const parsed = parseFrames('', frame(7, 'turn.start', { head: 'claudex' }));
    expect(parsed.frames).toEqual([{ id: 7, kind: 'turn.start', data: { head: 'claudex' } }]);
    expect(parsed.rest).toBe('');
    expect(parsed.dropped).toBe(0);
  });

  test('carries a frame split across two chunks instead of dropping or half-reading it', () => {
    const whole = frame(2, 'turn.end', { head: 'claudex', outcome: 'ok' });
    const cut = Math.floor(whole.length / 2);
    const first = parseFrames('', whole.slice(0, cut));
    expect(first.frames).toEqual([]);
    expect(first.rest).toBe(whole.slice(0, cut));

    const second = parseFrames(first.rest, whole.slice(cut));
    expect(second.frames).toEqual([{ id: 2, kind: 'turn.end', data: { head: 'claudex', outcome: 'ok' } }]);
    expect(second.rest).toBe('');
  });

  test('a heartbeat is not a frame: the link is alive and nothing happened', () => {
    const parsed = parseFrames('', ': heartbeat\n\n');
    expect(parsed.frames).toEqual([]);
    expect(parsed.dropped).toBe(0);
    expect(parsed.rest).toBe('');
  });

  test('reads several frames out of one chunk, heartbeat between them', () => {
    const parsed = parseFrames('', `${frame(1, 'head.state', { key: 'claudex' })}: beat\n\n${frame(2, 'account.switch', { head: 'claudex' })}`);
    expect(parsed.frames.map((f) => [f.id, f.kind])).toEqual([[1, 'head.state'], [2, 'account.switch']]);
    expect(parsed.rest).toBe('');
  });

  test('carries a kind this build does not know instead of dropping the frame', () => {
    const parsed = parseFrames('', frame(9, 'something.new', { a: 1 }));
    expect(parsed.frames).toEqual([{ id: 9, kind: 'something.new', data: { a: 1 } }]);
    expect(parsed.dropped).toBe(0);
  });

  test('counts what it cannot read rather than reporting silence', () => {
    const bad = [
      'id: 1\nevent: turn.start\ndata: {oops\n\n', // not JSON
      'id: 2\nkind: turn.start\ndata: {}\n\n', // no event name
      'id: 3\nevent: turn.start\n\n', // no data
      'id: 4\nevent: turn.start\ndata: 12\n\n', // not an object
    ].join('');
    const parsed = parseFrames('', bad);
    expect(parsed.frames).toEqual([]);
    expect(parsed.dropped).toBe(4);
  });

  test('reads a multi-line data field and a carriage return', () => {
    const parsed = parseFrames('', 'id: 5\r\nevent: message.edge\r\ndata: {"a": 1,\r\ndata: "b": 2}\r\n\r\n');
    expect(parsed.frames).toEqual([{ id: 5, kind: 'message.edge', data: { a: 1, b: 2 } }]);
  });

  test('an id that is not an integer leaves the resume point alone', () => {
    const parsed = parseFrames('', 'id: later\nevent: turn.start\ndata: {}\n\n');
    expect(parsed.frames[0].id).toBeNull();
  });
});

// ── the schedule ─────────────────────────────────────────────────────────────

describe('backoffMs', () => {
  test('starts at one second and doubles to the thirty second cap', () => {
    expect([0, 1, 2, 3, 4, 5, 6, 9].map((n) => backoffMs(n))).toEqual([
      1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000,
    ]);
  });

  test('never returns a wait that would spin', () => {
    expect(backoffMs(-3)).toBe(1_000);
    expect(backoffMs(1.7)).toBe(2_000);
  });
});

// ── the connection ───────────────────────────────────────────────────────────

describe('connect', () => {
  test('streams with the bearer, becomes live, and records the last frame', async () => {
    vi.useFakeTimers();
    const seen: RequestInit[] = [];
    const open = openStream();
    vi.stubGlobal('fetch', (_url: unknown, init?: RequestInit) => {
      seen.push(init ?? {});
      return Promise.resolve(new Response(open.body, { status: 200 }));
    });

    connect();
    await vi.advanceTimersByTimeAsync(0);
    expect(eventsStore.get().status).toBe('live');
    expect((seen[0].headers as Record<string, string>).Authorization).toBe('Bearer test-key');

    open.push(frame(4, 'turn.start', { head: 'claudex' }));
    await vi.advanceTimersByTimeAsync(0);
    const state = eventsStore.get();
    expect(state.lastId).toBe(4);
    expect(state.lastFrameAt).not.toBeNull();
    expect(state.dropped).toBe(0);
  });

  test('reopens after the stream ends, and sends Last-Event-ID so it resumes', async () => {
    vi.useFakeTimers();
    const seen: RequestInit[] = [];
    const open = openStream();
    vi.stubGlobal('fetch', (_url: unknown, init?: RequestInit) => {
      seen.push(init ?? {});
      const body = seen.length === 1 ? closedStream([frame(1, 'turn.start', {}), frame(2, 'turn.end', {})]) : open.body;
      return Promise.resolve(new Response(body, { status: 200 }));
    });

    connect();
    await vi.advanceTimersByTimeAsync(0);
    expect(eventsStore.get().lastId).toBe(2);

    // The first stream ended on its own: the loop is waiting out the first backoff step.
    expect(eventsStore.get().status).toBe('reconnecting');
    expect(seen).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(1_000);
    expect(seen).toHaveLength(2);
    expect((seen[1].headers as Record<string, string>)['Last-Event-ID']).toBe('2');
    expect(eventsStore.get().status).toBe('live');
  });

  test('a frame resets the backoff: the next reopen waits one second again', async () => {
    vi.useFakeTimers();
    const seen: RequestInit[] = [];
    vi.stubGlobal('fetch', (_url: unknown, init?: RequestInit) => {
      seen.push(init ?? {});
      // First attempt ends at once (no frame), second delivers a frame and ends.
      const body = seen.length === 1 ? closedStream([]) : closedStream([frame(1, 'turn.start', {})]);
      return Promise.resolve(new Response(body, { status: 200 }));
    });

    connect();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1_000); // attempt 2
    await vi.advanceTimersByTimeAsync(1_000); // attempt 3: one second, because attempt 2 delivered a frame
    expect(seen.length).toBe(3);
  });

  test('a 401 stops the stream and hands it to the shared client, which owns the lock', async () => {
    vi.useFakeTimers();
    const urls: string[] = [];
    vi.stubGlobal('fetch', (url: unknown) => {
      urls.push(String(url));
      if (String(url) === '/api/events') return Promise.resolve(new Response('nope', { status: 401 }));
      return Promise.resolve(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });

    connect();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(60_000);

    expect(eventsStore.get().status).toBe('off');
    // One stream attempt, then one read that lets the shared client see the 401. Nothing retries.
    expect(urls).toEqual(['/api/events', '/api/status']);
  });

  test('with no management key it does not open a stream at all', async () => {
    vi.stubGlobal('localStorage', { getItem: () => '', setItem: () => undefined });
    const urls: string[] = [];
    vi.stubGlobal('fetch', (url: unknown) => {
      urls.push(String(url));
      return Promise.resolve(new Response('', { status: 200 }));
    });
    connect();
    await Promise.resolve();
    expect(urls).toEqual([]);
    expect(eventsStore.get().status).toBe('off');
  });

  test('a second connect does not open a second stream', async () => {
    vi.useFakeTimers();
    const seen: RequestInit[] = [];
    const open = openStream();
    vi.stubGlobal('fetch', (_url: unknown, init?: RequestInit) => {
      seen.push(init ?? {});
      return Promise.resolve(new Response(open.body, { status: 200 }));
    });
    connect();
    connect();
    await vi.advanceTimersByTimeAsync(0);
    expect(seen).toHaveLength(1);
  });
});

// ── who hears about a frame ──────────────────────────────────────────────────

describe('subscribe', () => {
  test('delivers the kinds a listener asked for, and only those', async () => {
    vi.useFakeTimers();
    const open = openStream();
    vi.stubGlobal('fetch', () => Promise.resolve(new Response(open.body, { status: 200 })));

    const starts: EventFrame[] = [];
    const everything: EventFrame[] = [];
    const stopStarts = subscribe('turn.start', (f) => starts.push(f));
    subscribeAll((f) => everything.push(f));

    connect();
    await vi.advanceTimersByTimeAsync(0);
    open.push(frame(1, 'turn.start', { head: 'claudex' }));
    open.push(frame(2, 'turn.end', { head: 'claudex' }));
    await vi.advanceTimersByTimeAsync(0);

    expect(starts.map((f) => f.id)).toEqual([1]);
    expect(everything.map((f) => f.id)).toEqual([1, 2]);

    stopStarts();
    open.push(frame(3, 'turn.start', { head: 'claudex' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(starts.map((f) => f.id)).toEqual([1]); // the unsubscribe took effect
    expect(everything.map((f) => f.id)).toEqual([1, 2, 3]);
  });

  test('a frame of an unknown kind still counts as a frame for the connection', async () => {
    vi.useFakeTimers();
    const open = openStream();
    vi.stubGlobal('fetch', () => Promise.resolve(new Response(open.body, { status: 200 })));
    connect();
    await vi.advanceTimersByTimeAsync(0);
    open.push(frame(11, 'not.a.kind', { a: 1 }));
    await vi.advanceTimersByTimeAsync(0);
    expect(eventsStore.get().lastId).toBe(11);
  });
});

// ── the store ────────────────────────────────────────────────────────────────

describe('the connection store', () => {
  test('starts off, with nothing heard from yet', () => {
    // Read through `get`, not the hook: a hook called outside a React render has no dispatcher.
    const state = eventsStore.get();
    expect(state.status).toBe('off');
    expect(state.lastFrameAt).toBeNull();
    expect(state.lastBeatAt).toBeNull();
    expect(state.lastId).toBeNull();
  });

  test('disconnect puts it back to off', () => {
    connect();
    disconnect();
    expect(eventsStore.get().status).toBe('off');
  });
});
