// V4-239: the request drawer reads what `splice trace` and `splice wire` print, when the operator asks.
// Each key's action is proven to call its route through the real client (fetch stubbed, the daemon's
// answers as the routes build them); the views are proven to keep every body and every header set
// out of the document until its reveal is pressed, to label files kept from a capture since turned
// off, and to print a tap that is off as the daemon's sentence, never as an empty list.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { TracedTurnWire, TraceListWire, TraceRecord, TraceTurnWire, WireRead } from '../src/entities/perf';
import { CaptureRead, openTrace, openTraceTurn, openWire, TraceList, TraceTurn, WireList } from '../src/features/capture-read';

import { keysPut } from './lib/kotlin-views';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

const HEAD = 'claudex';
const UPSTREAM_BODY = '{"model":"m1","messages":[{"role":"user","content":"the secret plan"}]}';
const RESPONSE_TEXT = 'data: {"choices":[{"delta":{"content":"on it"}}]}';
const CLIENT_BODY = '{"messages":[{"role":"user","content":"client words"}]}';
const OFF = 'wire tap is off for head claudex: set [heads.claudex.overrides] wireTap = N (bodies to keep) and restart';

const ENDED: TracedTurnWire = {
  id: 'turn-1', ts: 1_789_725_600_000, session: 'alpha-session', model: 'm1', compact: false, open: false, outcome: 'ok', rounds: 1, attempts: 1, total_ms: 120,
};

/** GET /api/heads/claudex/trace as TraceRoute answers it: an ended turn and an open one, no body. */
const LIST: TraceListWire = {
  head: HEAD,
  files: '/home/op/.splice/state/trace/claudex-YYYY-MM-DD.jsonl',
  on_disk: 2,
  skipped_lines: 0,
  turns: [
    ENDED,
    { id: 'turn-2', ts: 1_789_725_660_000, session: null, model: 'm1', compact: false, open: true, outcome: null, rounds: 1, attempts: 1, total_ms: null },
  ],
};

/** GET /api/heads/claudex/trace?turn=turn-1: its attempt, then its turn record. */
const TURN: TraceTurnWire = {
  head: HEAD,
  turn: ENDED,
  records: [
    {
      kind: 'attempt', turn: 'turn-1', ts: 1_789_725_600_000, attempt: 1, round: 1, transport: 'sse',
      url: 'https://openrouter.ai/api/v1/chat/completions', durationMs: 40,
      request: { headers: { Authorization: '[redacted]', 'x-trace-header': 'hdr-value' }, body: UPSTREAM_BODY },
      response: { status: 200, headers: { 'x-request-id': 'r1' }, text: RESPONSE_TEXT },
    },
    {
      kind: 'turn', turn: 'turn-1', ts: 1_789_725_600_000, outcome: 'ok',
      client: { method: 'POST', path: '/v1/messages', headers: { authorization: '[redacted]' }, body: CLIENT_BODY },
      answer: { status: 200, stream: true, body: 'event: message_start' },
    },
  ],
};

const TAP: WireRead = {
  tap: { key: HEAD, keep: 5, records: [{ ts: 1_789_725_600_000, session: 's-1', model: 'm1', compact: false, body: UPSTREAM_BODY }] },
};

interface Sent {
  path: string;
  method: string;
}

function daemon(status: number, body: unknown, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    sent.push({ path: url, method: init?.method ?? 'GET' });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  });
}

describe('each key calls its route', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('Read trace is one GET of the head\'s trace, and its answer is the list', async () => {
    const sent: Sent[] = [];
    daemon(200, LIST, sent);
    const lists: TraceListWire[] = [];
    const faults: (string | null)[] = [];

    await openTrace(HEAD, (list) => lists.push(list), (fault) => faults.push(fault));

    expect(sent).toEqual([{ path: '/api/heads/claudex/trace', method: 'GET' }]);
    expect(lists).toEqual([LIST]);
    expect(faults).toEqual([null]);
  });

  test('Open turn asks for that one turn by id', async () => {
    const sent: Sent[] = [];
    daemon(200, TURN, sent);
    const turns: TraceTurnWire[] = [];

    await openTraceTurn(HEAD, 'turn-1', (read) => turns.push(read), () => undefined);

    expect(sent).toEqual([{ path: '/api/heads/claudex/trace?turn=turn-1', method: 'GET' }]);
    expect(turns).toEqual([TURN]);
  });

  test('Upstream bodies is one GET of the head\'s wire tap', async () => {
    const sent: Sent[] = [];
    daemon(200, TAP.tap, sent);
    const reads: WireRead[] = [];

    await openWire(HEAD, (read) => reads.push(read), () => undefined);

    expect(sent).toEqual([{ path: '/api/heads/claudex/wire', method: 'GET' }]);
    expect(reads).toEqual([TAP]);
  });

  test('a tap that is off is the daemon\'s sentence as a state, never a fault', async () => {
    daemon(409, { error: OFF }, []);
    const reads: WireRead[] = [];
    const faults: (string | null)[] = [];

    await openWire(HEAD, (read) => reads.push(read), (fault) => faults.push(fault));

    expect(reads).toEqual([{ off: OFF }]);
    expect(faults).toEqual([null]);
  });

  test('a refused read is kept in the daemon\'s words', async () => {
    daemon(500, { error: "cannot read claudex's trace under /x: Not a directory" }, []);
    const faults: (string | null)[] = [];

    await openTrace(HEAD, () => undefined, (fault) => faults.push(fault));

    expect(faults).toEqual(["cannot read claudex's trace under /x: Not a directory"]);
  });

  test('the drawer reads nothing until a key is pressed', () => {
    const sent: Sent[] = [];
    daemon(200, LIST, sent);

    const out = render(h(CaptureRead, { head: HEAD, capturing: true }));

    expect(out).toContain('>Read trace<');
    expect(out).toContain('>Upstream bodies<');
    expect(sent).toEqual([]);
  });
});

describe('what the views print', () => {
  test('the trace list is the verb\'s table: time, session tag, model, outcome, counts, and an open turn', () => {
    const out = render(h(TraceList, { list: LIST, capturing: true, onOpen: () => undefined }));

    expect(out).toContain('alpha-se');
    expect(out).not.toContain('alpha-session');
    expect(out).toContain('>ok<');
    expect(out).toContain('120ms');
    expect(out).toContain('>Open<');
    expect(out).toContain(LIST.files);
    expect(out).toContain('aria-label="Open turn turn-1"');
  });

  test('files kept from a capture since turned off say they were recorded earlier', () => {
    expect(render(h(TraceList, { list: LIST, capturing: false }))).toContain('Recorded earlier');
    expect(render(h(TraceList, { list: LIST, capturing: true }))).not.toContain('Recorded earlier');
  });

  test('a head with nothing on disk is an empty state naming what to do, never an empty table', () => {
    const out = render(h(TraceList, { list: { ...LIST, on_disk: 0, turns: [] }, capturing: false }));
    expect(out).toContain('No turns traced');
    expect(out).not.toContain('<table');
  });

  test('a turn\'s bodies and headers stay out of the document until revealed', () => {
    const out = render(h(TraceTurn, { read: TURN }));

    expect(out).toContain('https://openrouter.ai/api/v1/chat/completions');
    expect(out).toContain('aria-expanded="false"');
    for (const hidden of ['the secret plan', 'on it', 'client words', 'message_start', 'hdr-value', '[redacted]']) {
      expect(out, hidden).not.toContain(hidden);
    }
  });

  test('the wire tap lists each body by its size, the body itself behind its reveal', () => {
    const out = render(h(WireList, { read: TAP }));

    expect(out).toContain(`${UPSTREAM_BODY.length} chars`);
    expect(out).toContain('>m1<');
    expect(out).not.toContain('the secret plan');
  });

  test('a tap that is off prints the daemon\'s sentence', () => {
    const out = render(h(WireList, { read: { off: OFF } }));
    expect(out).toContain('Tap off');
    expect(out).toContain('[heads.claudex.overrides] wireTap = N');
  });
});

// The wire-keys probe reads the trace LIST live, but its isolated boot records no trace, so no turn id
// ever exists there to read `?turn=` with: that call site is dispositioned to this test
// (tools/e2e/probes/console-wire-keys.ts), which holds TraceTurnWire to the serializers that write it.
describe('a trace turn read is the daemon\'s own keys', () => {
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const ROUTE = 'features/turns/src/main/kotlin/splice/head/trace/TraceRoute.kt';
  const STORE = 'features/turns/src/main/kotlin/splice/head/wire/TraceStore.kt';
  const read = (file: string): string => readFileSync(path.join(repoRoot, file), 'utf8');
  const keys = (declared: object): string[] => Object.keys(declared).sort();
  // Every key each type declares, held exact by the typecheck: a key added to or dropped from the
  // type is a compile error here until this list says it too.
  const TURN_KEYS: Record<keyof TraceTurnWire, true> = { head: true, turn: true, records: true };
  const SUMMARY_KEYS: Record<keyof TracedTurnWire, true> = {
    id: true, ts: true, session: true, model: true, compact: true, open: true, outcome: true, rounds: true,
    attempts: true, total_ms: true,
  };
  // A record's required keys only: the rest are optional, and the probe checks presence, never absence.
  const RECORD_REQUIRED: TraceRecord = { kind: 'turn', turn: 'turn-1', ts: 0 };

  test('the turn read puts exactly TraceTurnWire\'s keys, and its turn is the summary the list prints', () => {
    expect(keysPut(read(ROUTE), 'turnJson(', ROUTE)).toEqual(keys(TURN_KEYS));
    expect(keysPut(read(ROUTE), 'summary(', ROUTE)).toEqual(keys(SUMMARY_KEYS));
  });

  test('every record the store writes carries the keys a TraceRecord requires', () => {
    expect(keysPut(read(STORE), 'stamp(', STORE)).toEqual(expect.arrayContaining(keys(RECORD_REQUIRED)));
  });
});
