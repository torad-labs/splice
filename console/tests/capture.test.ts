// M4-04: the per-head body-capture switch. GET/PUT /api/heads/{head}/capture carry a head's trace
// SETTINGS (CaptureRoutes.captureJson) and the daemon applies a write only at its next restart, so
// the rules under test are: the store holds the RE-READ as what runs and the PUT's answer as what
// was written, never the request; a refusal is kept in the daemon's words; and the drawer never
// prints a switch position, a sentence or a body the daemon did not report.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { captureView, fetchCapture, putCapture } from '../src/entities/perf';
import type { CaptureState, CaptureWire } from '../src/entities/perf';
import { afterRead, afterWrite } from '../src/entities/perf/model/capture';
import { captureStore } from '../src/entities/perf/model/store';
import { CAPTURE_AT_RESTART, CAPTURE_OFF, CAPTURE_ON, RequestDrawer } from '../src/widgets/waterfall';

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
    expect(captureStore.get().data).toEqual(state());
  });

  test('a write PUTs the switch, then re-reads, and the store takes the re-read as what runs', async () => {
    const calls = stub(wire(), { status: 200, body: wire({ enabled: true }) });
    await fetchCapture('e2e-codex');
    await putCapture('e2e-codex', true);
    expect(calls.map((call) => call.method)).toEqual(['GET', 'PUT', 'GET']);
    expect(JSON.parse(calls[1].body ?? '')).toEqual({ enabled: true });
    expect(captureStore.get().data).toEqual(state({ written: wire({ enabled: true }) }));
  });

  // Its own head: the store is the module's one store, and the write above is held for e2e-codex by
  // design (a re-read cannot report it), so a fresh head is what a fresh state looks like.
  test('a refused write is kept in the daemon\'s own words, and nothing reads as written', async () => {
    const reason = 'the body must be {"enabled": bool, "retention_days"?: int, "max_body_chars"?: int}';
    stub(wire({ head: 'refusing' }), { status: 400, body: { error: reason } });
    await fetchCapture('refusing');
    await putCapture('refusing', true);
    expect(captureStore.get().data).toEqual(state({ running: wire({ head: 'refusing' }), refused: reason }));
  });
});

describe('the request drawer', () => {
  test('capture is off by default and the drawer says so, with the switch named and unchecked', () => {
    const out = render(h(RequestDrawer, { capture: state(), onSwitch: () => undefined }));
    expect(out).toContain(CAPTURE_OFF);
    expect(out).toContain('role="switch"');
    expect(out).toContain('aria-checked="false"');
    expect(out).toContain('aria-label="body capture"');
    expect(out).toContain('4,194,304 chars');
    expect(out).toContain('7 d');
    expect(out).not.toContain(CAPTURE_AT_RESTART);
  });

  test('a write waiting on a restart checks the switch and still says capture is off', () => {
    const out = render(h(RequestDrawer, { capture: state({ written: wire({ enabled: true }) }), onSwitch: () => undefined }));
    expect(out).toContain('aria-checked="true"');
    expect(out).toContain(CAPTURE_OFF);
    expect(out).toContain(CAPTURE_AT_RESTART);
    expect(out).not.toContain(CAPTURE_ON);
  });

  test('capture running says so, and names the CLI as where bodies are read, since no route serves one', () => {
    const out = render(h(RequestDrawer, { capture: state({ running: wire({ enabled: true }) }), onSwitch: () => undefined }));
    expect(out).toContain(CAPTURE_ON);
    expect(out).toContain('no daemon route serves captured bodies');
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
