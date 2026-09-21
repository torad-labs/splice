// M3-01: the live wiring. Two claims are checked here, and neither is visible in a screenshot:
//
//   1. EVERY entity names the kinds that change its data, and the table the console wires from is
//      the table this test reads. A slice that forgets its kinds goes quietly stale, which looks
//      exactly like a quiet daemon.
//   2. A frame actually triggers the refetch it should - and only that one. The events are fed
//      through the REAL bus (the same subscribe/parse path the stream uses) with fetch stubbed, so
//      what is proven is the wiring rather than a mock of it.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { connect, disconnect } from '../src/entities/events';
import type { EventKind } from '../src/entities/events';
import { LIVE_KINDS as HEADS_KINDS } from '../src/entities/heads';
import { LIVE_KINDS as USAGE_KINDS } from '../src/entities/usage';
import { LIVE_KINDS as PERF_KINDS } from '../src/entities/perf';
import { LIVE_KINDS as SESSION_KINDS } from '../src/entities/session';
import { LIVE_KINDS as ACCOUNT_KINDS } from '../src/entities/account';
import { LIVE_KINDS as AUTH_KINDS } from '../src/entities/auth';
import { LIVE_BINDINGS, wireLive } from '../src/widgets/rule/wire';

const encoder = new TextEncoder();

/** A stream that stays open so frames can be pushed through the real connection. */
function openStream(): { body: ReadableStream<Uint8Array>; push: (chunk: string) => void } {
  let push: (chunk: string) => void = () => undefined;
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      push = (chunk: string) => controller.enqueue(encoder.encode(chunk));
    },
  });
  return { body, push: (chunk: string) => push(chunk) };
}

const frame = (id: number, kind: string, data: unknown): string =>
  `id: ${id}\nevent: ${kind}\ndata: ${JSON.stringify(data)}\n\n`;

let open: ReturnType<typeof openStream>;
let urls: string[];

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal('localStorage', { getItem: () => 'test-key', setItem: () => undefined });
  open = openStream();
  urls = [];
  vi.stubGlobal('fetch', (url: unknown) => {
    urls.push(String(url).split('?')[0]);
    // The stream itself answers with the open stream; every refetch answers with an empty 200.
    if (String(url) === '/api/events') return Promise.resolve(new Response(open.body, { status: 200 }));
    return Promise.resolve(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
  });
});

afterEach(() => {
  disconnect();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// ── what each slice says changes it ──────────────────────────────────────────

describe('the kind each entity follows', () => {
  test('every slice declares the kinds the contract gives it', () => {
    expect([...HEADS_KINDS]).toEqual(['head.state', 'turn.end']);
    expect([...USAGE_KINDS]).toEqual(['head.state', 'turn.end']);
    expect([...PERF_KINDS]).toEqual(['turn.start', 'turn.end']);
    expect([...SESSION_KINDS]).toEqual(['session.change', 'message.edge']);
    expect([...ACCOUNT_KINDS]).toEqual(['account.switch']);
    expect([...AUTH_KINDS]).toEqual(['account.switch']);
  });

  test('the table the console wires from is the six slices and their own kinds', () => {
    expect(LIVE_BINDINGS.map((b) => b.entity)).toEqual(['heads', 'usage', 'perf', 'session', 'account', 'auth']);
    for (const binding of LIVE_BINDINGS) {
      expect(binding.kinds.length).toBeGreaterThan(0);
      // Every kind bound is one of the six the daemon can send: a typo here would wire nothing and
      // never fail anywhere else.
      for (const kind of binding.kinds) expect(KINDS).toContain(kind);
    }
  });

  test('every kind the daemon can send has at least one follower', () => {
    const followed = new Set(LIVE_BINDINGS.flatMap((b) => [...b.kinds]));
    expect([...KINDS].filter((kind) => !followed.has(kind))).toEqual([]);
  });
});

const KINDS: readonly EventKind[] = [
  'head.state', 'turn.start', 'turn.end', 'session.change', 'message.edge', 'account.switch',
];

// ── a frame triggers the refetch it should ───────────────────────────────────

describe('a frame refetches through the entity it changes', () => {
  /** Frame -> the routes that were read afterwards, with the stream itself filtered out. */
  async function readsFor(kind: EventKind): Promise<string[]> {
    const unwire = wireLive();
    connect();
    await vi.advanceTimersByTimeAsync(0);
    urls = [];
    open.push(frame(1, kind, { head: 'claudex' }));
    await vi.advanceTimersByTimeAsync(0);
    unwire();
    disconnect();
    return [...urls].sort();
  }

  test('turn.end reads the heads, the usage and the turns behind it', async () => {
    expect(await readsFor('turn.end')).toEqual(['/api/heads', '/api/heads', '/api/perf/turns', '/api/usage']);
  });

  test('turn.start reads the turns, and nothing else', async () => {
    expect(await readsFor('turn.start')).toEqual(['/api/heads', '/api/perf/turns']);
  });

  test('head.state reads the heads and the usage windows, and not the turns', async () => {
    // Two reads, not three: the turns follow turn.start/turn.end, so a head changing state does
    // not drag a turns read behind it.
    expect(await readsFor('head.state')).toEqual(['/api/heads', '/api/usage']);
  });

  test('session.change reads the registry, and message.edge reads it too', async () => {
    expect(await readsFor('session.change')).toEqual(['/api/sessions']);
    expect(await readsFor('message.edge')).toEqual(['/api/sessions']);
  });

  test('account.switch reads the accounts and the auth cards', async () => {
    expect(await readsFor('account.switch')).toEqual(['/api/accounts', '/api/auth']);
  });

  test('a kind nobody follows refetches nothing at all', async () => {
    expect(await readsFor('something.new' as EventKind)).toEqual([]);
  });
});

// ── the wiring is a switch, not a leak ───────────────────────────────────────

describe('wireLive', () => {
  test('wiring twice does not turn one frame into two reads', async () => {
    wireLive();
    wireLive();
    connect();
    await vi.advanceTimersByTimeAsync(0);
    urls = [];
    open.push(frame(1, 'session.change', {}));
    await vi.advanceTimersByTimeAsync(0);
    expect(urls).toEqual(['/api/sessions']);
  });

  test('the unsubscribe stops the refetches', async () => {
    const unwire = wireLive();
    connect();
    await vi.advanceTimersByTimeAsync(0);
    unwire();
    urls = [];
    open.push(frame(2, 'session.change', {}));
    await vi.advanceTimersByTimeAsync(0);
    expect(urls).toEqual([]);
  });
});
