// THE LANES (operator ruling 4, item 5, 2026-09-25): one strand per head, sessions as cards on it,
// hand-offs as arcs between cards. The geometry is pure (shared/ui/lanes-geometry.ts), so each rule
// is held here against plain numbers; the page is held by a static render of its default view.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { SessionRow } from '../src/entities/session';
import { SessionsBoard } from '../src/pages/sessions';
import { lanesOf } from '../src/pages/sessions/select';
import { arcPath, columnsOf, crossings, newestArcs, sideOf } from '../src/shared/ui/lanes-geometry';

const h = React.createElement;
const T0 = 1_700_000_000_000;

function session(over: Partial<SessionRow> = {}): SessionRow {
  return {
    pid: 100,
    session_id: 'sid',
    name: 'implementer',
    kind: 'interactive',
    version: '2.1.257',
    cwd: '/home/user/dev/storefront',
    status: 'busy',
    status_updated_at: T0,
    started_at: T0,
    updated_at: T0,
    address: 'uds:/run/user/1000/cc-socks/100.sock',
    head: 'claudex',
    availability: 'live',
    ...over,
  };
}

describe('which hand-offs are drawn', () => {
  const cards = new Set(['a', 'b', 'c']);

  test('one arc per direction of a pair, at its newest message, oldest arc first', () => {
    const arcs = newestArcs([
      { from: 'a', to: 'b', at: 1 },
      { from: 'a', to: 'b', at: 5 },
      { from: 'b', to: 'a', at: 3 },
      { from: 'c', to: 'a', at: 2 },
    ], cards);
    expect(arcs.map((arc) => `${arc.key}@${arc.at}`)).toEqual(['c>a@2', 'b>a@3', 'a>b@5']);
  });

  test('a message with an end that is not a card is not drawn, and neither is one to itself', () => {
    expect(newestArcs([{ from: 'a', to: 'gone', at: 1 }, { from: 'a', to: 'a', at: 2 }], cards)).toEqual([]);
  });

  test('a pair drawn both ways leaves opposite sides of centre; a lone arc leaves the centre', () => {
    const arcs = newestArcs([{ from: 'a', to: 'b', at: 1 }, { from: 'b', to: 'a', at: 2 }, { from: 'c', to: 'a', at: 3 }], cards);
    const side = (key: string) => arcs.filter((arc) => arc.key === key).map((arc) => sideOf(arc, arcs))[0];
    expect([side('a>b'), side('b>a'), side('c>a')]).toEqual([1, -1, 0]);
  });
});

describe('when a dot crosses', () => {
  const arcs = newestArcs([{ from: 'a', to: 'b', at: 5 }, { from: 'b', to: 'a', at: 3 }], new Set(['a', 'b']));

  test('a pair whose newest message moved, or a pair that is new, crossed; one that stood still did not', () => {
    expect(crossings(new Map([['a>b', 4], ['b>a', 3]]), arcs)).toEqual(['a>b']);
    expect(crossings(new Map([['a>b', 5]]), arcs)).toEqual(['b>a']);
    expect(crossings(new Map([['a>b', 5], ['b>a', 3]]), arcs)).toEqual([]);
  });
});

describe('where each card stands', () => {
  test('one column per card across every lane, in start order, undated after the dated', () => {
    const columns = columnsOf([
      [{ key: 'impl', start: 10 }, { key: 'mig', start: 40 }],
      [{ key: 'rev', start: 20 }],
      [{ key: 'arch', start: null }, { key: 'test', start: 30 }],
    ]);
    expect(Object.fromEntries(columns)).toEqual({ impl: 0, rev: 1, test: 2, mig: 3, arch: 4 });
  });
});

describe('the curve between two cards', () => {
  const box = (left: number, top: number) => ({ left, top, width: 120, height: 60 });

  test('down a lane: from the sender\'s bottom edge to the receiver\'s top, bending half the gap', () => {
    expect(arcPath(box(0, 0), box(300, 160))).toBe('M60 60 C60 110 360 110 360 160');
  });

  test('up a lane: from the sender\'s top edge to the receiver\'s bottom', () => {
    expect(arcPath(box(300, 160), box(0, 0))).toBe('M360 160 C360 110 60 110 60 60');
  });

  test('within one row: an arch over both cards, never higher than its ceiling', () => {
    expect(arcPath(box(0, 100), box(120, 100))).toBe('M60 100 C60 60 180 60 180 100');
    expect(arcPath(box(0, 100), box(1200, 100))).toBe('M60 100 C60 36 1260 36 1260 100');
  });

  test('a pair drawn both ways leaves off centre, by a sixth of the card', () => {
    expect(arcPath(box(0, 0), box(300, 160), 1)).toBe('M80 60 C80 110 380 110 380 160');
  });
});

describe('the sessions page draws lanes by default', () => {
  test('every head the registry lists is a lane in registry order, the headless ones last', () => {
    const rows = [
      session({ session_id: 'a', head: 'claude-grok' }),
      session({ session_id: 'b', head: 'unknown head' }),
      session({ session_id: 'c', head: 'side-head' }),
    ];
    const lanes = lanesOf(rows, ['claudex', 'claude-grok']);
    expect(lanes.map((lane) => `${lane.key}:${lane.count}`)).toEqual(['claudex:0', 'claude-grok:1', 'side-head:1', 'unknown head:1']);
  });

  test('each session is a card on its head\'s lane, named, with its project and its state word', () => {
    const out = renderToStaticMarkup(h(SessionsBoard, {
      payload: {
        note: 'headless `claude -p` runs never register',
        sessions: [
          session({ session_id: 'a', name: 'implementer' }),
          session({ session_id: 'b', name: 'reviewer', head: 'claude-grok', availability: 'stale', cwd: '/home/user/dev/payments-api' }),
        ],
      },
    }));
    expect(out).toContain('class="myx-lanes"');
    expect(out.match(/class="myx-lane myx-hue-/g)?.length).toBe(2);
    expect(out).toContain('aria-label="claudex implementer, Live"');
    expect(out).toContain('myx-lane-card-title">reviewer<');
    expect(out).toContain('myx-lane-card-meta">payments-api<');
    // the one that needs the operator is tinted, as its row is on the board
    expect(out.match(/myx-lane-card-warn/g)?.length).toBe(1);
    expect(out).not.toContain('<table');
  });
});
