// M4-05: the sessions page reads GET /api/sessions/edges, every registry session's message edges in
// one payload (ActivityRoutes.boardEdges: {sessions: {<session id>: SessionEdge[]}}), so the peer
// column prints for EVERY row instead of only the opened one.
//
// And the defect the live payload exposed: an edge's two ends are not the same kind of value.
// MessageEdge.from is the SENDING SESSION'S ID (MessageEdgeStore.kt: "[from] is the sending session
// id"); `to` is the address (or the name) the SendMessage call used. A received edge's peer is
// therefore found by session id, never by address; resolving it by address matched nothing and
// printed the receiver's own socket as its peer.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchBoardEdges, latestPeer, peerLabel } from '../src/entities/session';
import type { SessionEdge, SessionRow } from '../src/entities/session';
import { boardEdgesStore } from '../src/entities/session/model/store';
import { SessionsBoard } from '../src/pages/sessions';

const h = React.createElement;
const T0 = 1_790_000_000_000;

function session(over: Partial<SessionRow> & { session_id: string }): SessionRow {
  return {
    pid: 100,
    name: null,
    kind: 'interactive',
    version: '2.1.257',
    cwd: '/tmp/e2e-repo',
    status: 'idle',
    status_updated_at: null,
    started_at: T0,
    updated_at: T0,
    address: null,
    head: 'unknown head',
    availability: 'live',
    ...over,
  };
}

// The two registrations and the one hand-off the e2e stack produced on 2026-09-23, as the live
// daemon reported them.
const SENDER = session({ session_id: 'e2e5e11d-0000-4000-8000-000000000001', name: 'e2e-sender', address: 'uds:/tmp/s/e2e-sender.sock' });
const PEER = session({ session_id: 'e2e9ee12-0000-4000-8000-000000000002', name: 'e2e-peer', address: 'uds:/tmp/s/e2e-peer.sock' });
const OUT: SessionEdge = { from: SENDER.session_id ?? '', to: 'uds:/tmp/s/e2e-peer.sock', at: T0 + 1_000, direction: 'out' };
const IN: SessionEdge = { ...OUT, direction: 'in' };

describe('the peer of an edge', () => {
  test('a sent edge names the session that owns the address it was sent to', () => {
    expect(peerLabel([SENDER, PEER], OUT)).toBe('e2e-peer');
  });

  test('a sent edge to an address no session owns prints the address the call used', () => {
    expect(peerLabel([SENDER], OUT)).toBe('uds:/tmp/s/e2e-peer.sock');
  });

  test('a received edge names its SENDER, found by session id', () => {
    expect(peerLabel([SENDER, PEER], IN)).toBe('e2e-sender');
  });

  test('a received edge from a session the registry no longer holds prints its session tag', () => {
    expect(peerLabel([PEER], IN)).toBe('e2e5e11d');
  });

  test('the latest peer is the other end of the newest edge, and no edges is no peer', () => {
    const older: SessionEdge = { from: PEER.session_id ?? '', to: 'uds:/tmp/s/e2e-sender.sock', at: T0, direction: 'in' };
    expect(latestPeer([SENDER, PEER], [older, IN])).toBe('e2e-sender');
    expect(latestPeer([SENDER, PEER], [])).toBeNull();
  });
});

describe('the board-wide edges', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('are read in one request and held as the daemon sent them', async () => {
    const body = { sessions: { [PEER.session_id ?? '']: [IN], [SENDER.session_id ?? '']: [OUT] } };
    const urls: string[] = [];
    vi.stubGlobal('fetch', (input: unknown): Promise<Response> => {
      urls.push(String(input));
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } }));
    });
    await fetchBoardEdges();
    expect(urls).toEqual(['/api/sessions/edges']);
    expect(boardEdgesStore.get().data).toEqual(body);
  });

  test('print every row\'s peer on the board with no session opened', () => {
    const out = renderToStaticMarkup(
      h(SessionsBoard, {
        payload: { note: 'headless runs never register', sessions: [SENDER, PEER] },
        boardEdges: { sessions: { [PEER.session_id ?? '']: [IN], [SENDER.session_id ?? '']: [OUT] } },
      }),
    );
    // Each strip carries its own name and its peer's; the peer column is the only place the OTHER
    // name can come from.
    const strip = (name: string) => out.slice(out.indexOf(`aria-label="sessions ${name}"`)).split('</div></div>')[0];
    expect(strip('e2e-sender')).toContain('e2e-peer');
    expect(strip('e2e-peer')).toContain('e2e-sender');
  });

  test('a failed edges read is printed, and the peers stay unknown rather than invented', () => {
    const out = renderToStaticMarkup(
      h(SessionsBoard, {
        payload: { note: 'headless runs never register', sessions: [SENDER] },
        boardEdges: null,
        edgesError: 'the activity stores are not wired into this control plane',
      }),
    );
    expect(out).toContain('the activity stores are not wired into this control plane');
    expect(out).toContain('>n/r<');
  });
});
