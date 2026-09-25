// THE LANES (operator ruling 4, item 5, 2026-09-25): one strand per head, sessions as cards on it,
// hand-offs as arcs between cards. The geometry is pure (shared/ui/lanes-geometry.ts), so each rule
// is held here against plain numbers; the page is held by a static render of its default view.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { SessionRow } from '../src/entities/session';
import { SessionsBoard } from '../src/pages/sessions';
import { lanesOf } from '../src/pages/sessions/select';
import { Lanes } from '../src/shared/ui/lanes';
import type { Lane, LaneCard } from '../src/shared/ui/lanes';
import { arcPath, columnsOf, crossings, newestArcs, readOf, sideOf, trackTemplate } from '../src/shared/ui/lanes-geometry';

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

describe('what a read of the hand-offs takes note of (Hitstop, 2026-09-25)', () => {
  const cards = new Set(['a', 'b', 'c']);
  const old = [{ from: 'a', to: 'b', at: 5 }, { from: 'c', to: 'a', at: 7 }];

  test('hand-offs not read yet are no read: nothing seen, nothing crossed', () => {
    expect(readOf(null, null, [])).toEqual({ seen: null, crossed: [] });
  });

  test('the first read only takes note, so opening the board flies nothing', () => {
    const first = readOf(null, old, newestArcs(old, cards));
    expect(first.crossed).toEqual([]);
    expect(Object.fromEntries(first.seen ?? [])).toEqual({ 'a>b': 5, 'c>a': 7 });
  });

  test('a read after it flies what is new, and only that', () => {
    const first = readOf(null, old, newestArcs(old, cards));
    const next = [...old, { from: 'a', to: 'b', at: 9 }];
    expect(readOf(first.seen, next, newestArcs(next, cards)).crossed).toEqual(['a>b']);
  });

  test('a card that appears after its hand-off does not fly that hand-off again', () => {
    // c is not a card at the first read (the registry listed it late), so c>a is not drawn then.
    const first = readOf(null, old, newestArcs(old, new Set(['a', 'b'])));
    expect(readOf(first.seen, old, newestArcs(old, cards)).crossed).toEqual([]);
  });
});

describe('where each card stands', () => {
  test('one column per dated card across every lane, in start order', () => {
    const columns = columnsOf([
      [{ key: 'impl', start: 10 }, { key: 'mig', start: 40 }],
      [{ key: 'rev', start: 20 }],
      [{ key: 'test', start: 30 }],
    ]);
    expect(Object.fromEntries(columns.at)).toEqual({ impl: 0, rev: 1, test: 2, mig: 3 });
    expect(trackTemplate(columns)).toBe('repeat(4, minmax(var(--lane-card-min), 1fr))');
  });

  test('an undated card claims no start: it stands apart, after a gap column, in lane order', () => {
    // Marlin, 2026-09-25: standing undated cards after the dated ones in lane order told the reader
    // they started later, and in that order.
    const columns = columnsOf([
      [{ key: 'impl', start: 10 }, { key: 'open', start: null }],
      [{ key: 'arch', start: null }, { key: 'rev', start: 20 }],
    ]);
    expect(Object.fromEntries(columns.at)).toEqual({ impl: 0, rev: 1, open: 3, arch: 4 });
    expect(columns).toMatchObject({ dated: 2, undated: 2 });
    expect(trackTemplate(columns)).toBe('repeat(2, minmax(var(--lane-card-min), 1fr)) var(--lane-gap) repeat(2, minmax(var(--lane-card-min), 1fr))');
    // all undated: no gap, and nothing dated to stand apart from
    expect(Object.fromEntries(columnsOf([[{ key: 'a', start: null }], [{ key: 'b', start: null }]]).at)).toEqual({ a: 0, b: 1 });
  });

  test('the undated cards are captioned as start unknown, and a board of dated cards has no caption', () => {
    const lane = (key: string, start: number | null): Lane => ({
      key, name: key, title: key, hue: 'myx-hue-1',
      cards: [{ key: `${key}-card`, title: `${key} card`, meta: null, tone: 'ok', word: 'Live', start }],
    });
    const drawn = (lanes: Lane[]) => renderToStaticMarkup(h(Lanes, { lanes, messages: [], label: 'Board', cardLabel: (card: LaneCard) => card.title }));
    const mixed = drawn([lane('claudex', 10), lane('claude-grok', null)]);
    expect(mixed).toContain('>Start unknown<');
    expect(mixed).toMatch(/myx-lanes-unknown" style="grid-column:3 \/ span 1"/);
    expect(drawn([lane('claudex', 10), lane('claude-grok', 20)])).not.toContain('Start unknown');
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

describe('the paint order', () => {
  // splice-lead at 2560, 2026-09-25: an arc drawn over a card read as striking its words through,
  // even with each word on a patch of the card's ground. The arcs now pass UNDER the cards.
  const sheet = readFileSync(fileURLToPath(new URL('../src/shared/ui/lanes.css', import.meta.url)), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '');
  const rule = (selector: string): string => {
    const at = sheet.search(new RegExp(`(^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{`));
    return at < 0 ? '' : sheet.slice(sheet.indexOf('{', at) + 1, sheet.indexOf('}', at));
  };
  const z = (selector: string): number => Number(/z-index:\s*(-?\d+)/.exec(rule(selector))?.[1] ?? NaN);

  test('every card stands over every arc, on a ground the arc cannot show through', () => {
    expect(z('.myx-lane-card')).toBeGreaterThan(z('.myx-lanes-arcs'));
    expect(rule('.myx-lane-card')).toMatch(/position:\s*relative/);
    expect(rule('.myx-lane-card')).toMatch(/background:\s*var\(--lane-ground\)/);
  });

  test('a dot in flight stands over every card, so it never sinks into one between its ends', () => {
    // Hitstop, 2026-09-25: under the cards, a dot vanished into the card between sender and
    // receiver and came out of it, reading as that card's hand-off.
    expect(z('.myx-lanes-flights')).toBeGreaterThan(z('.myx-lane-card'));
    expect(rule('.myx-lanes-flights')).toMatch(/pointer-events:\s*none/);
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

describe('the fleet\'s hand-offs', () => {
  // splice-lead at 2560, 2026-09-25: From and To were 40% each of the page's width, 650 px of nothing
  // between two short names. Three short facts a row: the table is as wide as they are.
  test('the table carries no column widths, and its frame fits its content', () => {
    const out = renderToStaticMarkup(h(SessionsBoard, {
      payload: { note: '', sessions: [session({ session_id: 'a', name: 'implementer' }), session({ session_id: 'b', name: 'architect', address: 'uds:/b.sock' })] },
      boardEdges: { sessions: { a: [{ from: 'a', to: 'uds:/b.sock', at: T0, direction: 'out' }] } },
    }));
    const table = /<table[^>]*aria-label="Hand-offs"[\s\S]*?<\/table>/.exec(out)?.[0] ?? '';
    expect(table).toContain('>architect<'); // the denominator: the table was drawn, with its row
    expect(table).not.toMatch(/<col[^>]*width/);
    const sheet = readFileSync(fileURLToPath(new URL('../src/pages/sessions/sessions.css', import.meta.url)), 'utf8');
    expect(sheet).toMatch(/\.myx-sx-handoffs \.myx-dt-wrap \{ width: fit-content; max-width: 100%; \}/);
  });

  test('past 3000 the hand-offs column is as wide as its table, and the lanes take the rest', () => {
    // A fixed share of the page left the fitted table in half an empty column at 3840.
    const sheet = readFileSync(fileURLToPath(new URL('../src/pages/sessions/sessions.css', import.meta.url)), 'utf8');
    const wide = /@media \(min-width: 3000px\) \{([\s\S]*?)\n\}/.exec(sheet)?.[1] ?? '';
    expect(wide).toMatch(/\.myx-sx \{[^}]*grid-template-columns: minmax\(0, 1fr\) fit-content\(40%\);/);
  });
});
