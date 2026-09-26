// M4-04: the per-head body-capture switch. GET/PUT /api/heads/{head}/capture carry a head's trace
// SETTINGS (CaptureRoutes.captureJson) and the daemon applies a write only at its next restart, so
// the rules under test are: the store holds the RE-READ as what runs and the PUT's answer as what
// was written, never the request; a refusal is kept in the daemon's words; and the drawer never
// prints a switch position, a sentence or a body the daemon did not report.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { captureFor, captureView, fetchCapture, putCapture } from '../src/entities/perf';
import type { CaptureState, CaptureWire } from '../src/entities/perf';
import { afterRead, afterWrite } from '../src/entities/perf/model/capture';
import { captureStore } from '../src/entities/perf/model/store';
import { CAPTURE_AT_RESTART, CAPTURE_ON, RequestDrawer } from '../src/widgets/waterfall';
import { LogsBoard } from '../src/pages/logs';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

/** The GET answer the live daemon gave on 2026-09-23 for a head that never opted in. */
function wire(over: Partial<CaptureWire> = {}): CaptureWire {
  return { head: 'e2e-codex', enabled: false, retention_days: 7, max_body_chars: 4_194_304, restart_required: true, ...over };
}

function state(over: Partial<CaptureState> = {}): CaptureState {
  return { running: wire(), written: null, refused: null, writing: false, ...over };
}

describe('capture state', () => {
  test('a read of a head holds what runs and nothing written', () => {
    expect(afterRead(null, wire())).toEqual(state());
  });

  test('a re-read of the same head keeps what this console wrote, since GET cannot report it', () => {
    const written = wire({ enabled: true });
    expect(afterRead(state({ written }), wire()).written).toEqual(written);
  });

  test('a read of another head drops the pending write and the refusal of the first', () => {
    const other = afterRead(state({ written: wire({ enabled: true }), refused: 'no' }), wire({ head: 'other' }));
    expect(other).toEqual({ running: wire({ head: 'other' }), written: null, refused: null, writing: false });
  });

  test('a write stores its answer as written and the re-read as running', () => {
    const answer = wire({ enabled: true });
    expect(afterWrite(state({ writing: true }), { ok: true, answer }, wire())).toEqual(state({ written: answer }));
  });

  test('a refused write keeps the reason verbatim and the previous written value', () => {
    const before = wire({ enabled: true });
    const reason = 'heads.e2e-codex.port: port 1 is below 1024';
    expect(afterWrite(state({ written: before }), { ok: false, reason }, wire())).toEqual(
      state({ written: before, refused: reason }),
    );
  });

  test('the switch shows the written value, and a restart is pending while it differs from what runs', () => {
    expect(captureView(state())).toEqual({ asked: false, running: false, awaitingRestart: false });
    expect(captureView(state({ written: wire({ enabled: true }) }))).toEqual({ asked: true, running: false, awaitingRestart: true });
    // Written off while off runs: nothing to wait for.
    expect(captureView(state({ written: wire({ enabled: false }) }))).toEqual({ asked: false, running: false, awaitingRestart: false });
    // A daemon that applied the write hot reports it on the re-read: nothing pending either.
    const hot = wire({ enabled: true, restart_required: false });
    expect(captureView(state({ running: hot, written: hot }))).toEqual({ asked: true, running: true, awaitingRestart: false });
  });
});

describe('capture reads and writes', () => {
  afterEach(() => vi.unstubAllGlobals());

  /** A daemon whose capture route answers GET with `running` and PUT with `put`. */
  function stub(running: CaptureWire, put: { status: number; body: unknown }): { method: string; url: string; body: string | null }[] {
    const calls: { method: string; url: string; body: string | null }[] = [];
    vi.stubGlobal('fetch', (input: unknown, init?: RequestInit): Promise<Response> => {
      const method = init?.method ?? 'GET';
      calls.push({ method, url: String(input), body: typeof init?.body === 'string' ? init.body : null });
      const answer = method === 'PUT' ? put : { status: 200, body: running };
      return Promise.resolve(new Response(JSON.stringify(answer.body), { status: answer.status, headers: { 'content-type': 'application/json' } }));
    });
    return calls;
  }

  test('a read asks the route with no query: it serves settings and takes no turn', async () => {
    const calls = stub(wire(), { status: 200, body: wire() });
    await fetchCapture('e2e-codex');
    expect(calls).toEqual([{ method: 'GET', url: '/api/heads/e2e-codex/capture', body: null }]);
    expect(captureStore.get().data?.state).toEqual(state());
  });

  test('a write PUTs the switch, then re-reads, and the store takes the re-read as what runs', async () => {
    const calls = stub(wire(), { status: 200, body: wire({ enabled: true }) });
    await fetchCapture('e2e-codex');
    await putCapture('e2e-codex', true);
    expect(calls.map((call) => call.method)).toEqual(['GET', 'PUT', 'GET']);
    expect(JSON.parse(calls[1].body ?? '')).toEqual({ enabled: true });
    expect(captureStore.get().data?.state).toEqual(state({ written: wire({ enabled: true }) }));
  });

  // Its own head: the store is the module's one store, and the write above is held for e2e-codex by
  // design (a re-read cannot report it), so a fresh head is what a fresh state looks like.
  test('a refused write is kept in the daemon\'s own words, and nothing reads as written', async () => {
    const reason = 'the body must be {"enabled": bool, "retention_days"?: int, "max_body_chars"?: int}';
    stub(wire({ head: 'refusing' }), { status: 400, body: { error: reason } });
    await fetchCapture('refusing');
    await putCapture('refusing', true);
    expect(captureStore.get().data?.state).toEqual(state({ running: wire({ head: 'refusing' }), refused: reason }));
  });
});

describe("one head's capture failure is that head's alone (V4-301)", () => {
  // The drawers gated the capture DATA by head but passed the store's one error straight through, and
  // that error cleared only on the next successful read of ANY head: a turn opened on head A whose
  // capture GET failed, then a turn on head B, printed A's failure under B while B's read was in
  // flight, and for good if it never landed. The logs page reads the same store.
  const ALPHA_DOWN = 'alpha: the trace store is unreadable';
  const BETA_DOWN = 'beta: the trace store is unreadable';
  beforeEach(() => captureStore.setData({ state: null, failures: new Map() }));
  afterEach(() => vi.unstubAllGlobals());

  /** A daemon answering each head's capture route: its settings, a failure in the daemon's words,
   *  or no answer at all (a read left in flight). */
  type Route = CaptureWire | { down: string } | 'hang';
  function daemon(routes: Record<string, Route>): void {
    vi.stubGlobal('fetch', (input: unknown, init?: RequestInit): Promise<Response> => {
      const head = decodeURIComponent(String(input).split('/')[3] ?? '');
      const route = routes[head];
      if (route === 'hang') return new Promise<Response>(() => undefined);
      const body = route === undefined || 'down' in route ? { error: route === undefined ? 'unknown head' : route.down } : init?.method === 'PUT' ? { ...route, enabled: true } : route;
      const status = route === undefined || 'down' in route ? 503 : 200;
      return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }));
    });
  }
  const view = (head: string) => captureFor(captureStore.get().data, head);
  const logsDrawer = (head: string): string => render(h(LogsBoard, {
    payload: { key: head, path: '/home/user/.splice/logs/daemon.log', lines: [] },
    filter: { head: null, level: null, substring: '' },
    follow: true, appended: 0, reset: false, tags: [], levels: [], head, tail: 200,
    heads: [{ key: 'alpha', label: 'alpha' }, { key: 'beta', label: 'beta' }],
    capture: captureStore.get().data,
  }));

  test("head A's failed read never reads as head B's error while B's read is in flight", async () => {
    daemon({ alpha: { down: ALPHA_DOWN }, beta: 'hang' });
    await fetchCapture('alpha');
    void fetchCapture('beta');
    expect(view('beta')).toEqual({ capture: null, error: null });
    expect(view('alpha')).toEqual({ capture: null, error: ALPHA_DOWN });
  });

  test("the logs page's drawer prints the tailed head's failure, and never another head's", async () => {
    daemon({ alpha: { down: ALPHA_DOWN }, beta: 'hang' });
    await fetchCapture('alpha');
    void fetchCapture('beta');
    expect(logsDrawer('beta')).not.toContain(ALPHA_DOWN);
    expect(logsDrawer('alpha')).toContain(ALPHA_DOWN);
  });

  test("B's own failure is B's, and A's stays A's", async () => {
    daemon({ alpha: { down: ALPHA_DOWN }, beta: { down: BETA_DOWN } });
    await fetchCapture('alpha');
    await fetchCapture('beta');
    expect(view('alpha').error).toBe(ALPHA_DOWN);
    expect(view('beta').error).toBe(BETA_DOWN);
  });

  test("a read of B leaves A's failure on A, until A is read again", async () => {
    daemon({ alpha: { down: ALPHA_DOWN }, beta: wire({ head: 'beta' }) });
    await fetchCapture('alpha');
    await fetchCapture('beta');
    expect(view('alpha')).toEqual({ capture: null, error: ALPHA_DOWN });
    expect(view('beta')).toEqual({ capture: state({ running: wire({ head: 'beta' }) }), error: null });
    daemon({ alpha: wire({ head: 'alpha' }) });
    await fetchCapture('alpha');
    expect(view('alpha')).toEqual({ capture: state({ running: wire({ head: 'alpha' }) }), error: null });
  });

  test('a write whose re-read fails files the failure under the head it wrote', async () => {
    daemon({ beta: wire({ head: 'beta' }) });
    await fetchCapture('beta');
    daemon({ alpha: { down: ALPHA_DOWN }, beta: wire({ head: 'beta' }) });
    await putCapture('alpha', true);
    expect(view('alpha').error).toBe(ALPHA_DOWN);
    expect(view('beta')).toEqual({ capture: state({ running: wire({ head: 'beta' }) }), error: null });
  });
});

describe('the request drawer', () => {
  test('capture is off by default: the switch, named and unchecked, says so alone', () => {
    // Off and running off is the switch's own word; a badge beside it said the same thing twice.
    const out = render(h(RequestDrawer, { capture: state(), onSwitch: () => undefined }));
    expect(out).toContain('role="switch"');
    expect(out).toContain('aria-checked="false"');
    expect(out).toContain('aria-label="Body capture"');
    expect(out).not.toContain(CAPTURE_ON);
    expect(out).toContain('4,194,304 chars');
    expect(out).toContain('7 d');
    expect(out).not.toContain(CAPTURE_AT_RESTART);
  });

  test('a write waiting on a restart checks the switch and says it runs only after one', () => {
    const out = render(h(RequestDrawer, { capture: state({ written: wire({ enabled: true }) }), onSwitch: () => undefined }));
    expect(out).toContain('aria-checked="true"');
    expect(out).toContain(CAPTURE_AT_RESTART);
    expect(out).not.toContain(CAPTURE_ON);
  });

  test('capture running says so, and names the CLI as where bodies are read, since no route serves one', () => {
    const out = render(h(RequestDrawer, { capture: state({ running: wire({ enabled: true }) }), onSwitch: () => undefined }));
    expect(out).toContain(CAPTURE_ON);
    expect(out).toContain('>Read bodies<');
    expect(out).toContain('splice trace e2e-codex');
  });

  test('a refusal prints the daemon\'s sentence verbatim', () => {
    const reason = 'splice.toml does not parse: line 3';
    expect(render(h(RequestDrawer, { capture: state({ refused: reason }), onSwitch: () => undefined }))).toContain(reason);
  });

  test('a failed read with nothing held prints the failure, never an empty switch', () => {
    const out = render(h(RequestDrawer, { capture: null, error: 'HTTP 503' }));
    expect(out).toContain('HTTP 503');
    expect(out).not.toContain('role="switch"');
  });

  test('a drawer with no writer shows the switch and cannot be pressed', () => {
    expect(render(h(RequestDrawer, { capture: state() }))).toContain('disabled=""');
  });
});
