// WALLS for the draining restart (M4-02, M4-03): the one control the fleet's head detail and the
// doctor's upgrade section share.
//
// The load-bearing one is `a refusal reaches the operator in the daemon's own words`. The daemon
// REFUSES a restart it cannot bring back (DaemonRoutes.kt:95-97: nothing supervises a hand-started
// daemon, so a drain would be a stop), and a console that printed `HTTP 409` or a generic failure in
// place of that sentence would hide the one fact the operator needs: why nothing happened. So the
// answer is asserted through the REAL client path with a stubbed transport, not through a predicate.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { restartDaemon } from '../src/entities/daemon';
import { DaemonRestart } from '../src/features/daemon-restart';
import { MgmtError } from '../src/shared/api';

/** DaemonRoutes.kt:64-65 and :58-60, verbatim: the two refusals are different facts. */
const UNSUPERVISED = 'nothing will restart this daemon: it was not started by systemd, so a drain would leave it down';
const UNWIRED = 'the daemon did not wire a supervision probe; /api/daemon/restart cannot tell whether anything '
  + 'would bring it back, and refusing is the only honest answer';

interface Sent {
  path: string;
  method: string | undefined;
}

function transport(status: number, body: unknown, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    sent.push({ path, method: init?.method });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  });
}

describe('POST /api/daemon/restart through the real client', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('a restart the daemon took on answers the status word it wrote, from one POST', async () => {
    const sent: Sent[] = [];
    transport(202, { status: 'draining' }, sent);
    expect(await restartDaemon()).toEqual({ status: 'draining' });
    expect(sent).toEqual([{ path: '/api/daemon/restart', method: 'POST' }]);
  });

  test('an unsupervised daemon\'s refusal reaches the caller as its own sentence', async () => {
    transport(409, { error: UNSUPERVISED }, []);
    const refusal = await restartDaemon().then(() => null, (err: unknown) => err);
    expect(refusal).toBeInstanceOf(MgmtError);
    expect((refusal as MgmtError).message).toBe(UNSUPERVISED);
    expect((refusal as MgmtError).status).toBe(409);
  });

  test('the unwired refusal is its own sentence, not the unsupervised one', async () => {
    transport(503, { error: UNWIRED }, []);
    await expect(restartDaemon()).rejects.toThrow(UNWIRED);
  });
});

describe('the restart control', () => {
  test('rests as one key naming the daemon, and nothing is armed or sent until it is pressed', () => {
    const out = renderToStaticMarkup(React.createElement(DaemonRestart));
    expect(out).toContain('restart daemon');
    // The confirmation is inline and arrives with the first press, never pre-rendered and never a
    // dialog (CONTRACTS.md: restart actions confirm inline on the strip).
    expect(out).not.toContain('drain and restart');
    expect(out).not.toContain('role="dialog"');
  });
});
