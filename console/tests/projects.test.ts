// THE TWO BOARD TABLES FILL THEIR WIDTH AND KEEP THEIR SHAPE, READ OFF THE MARKUP.
//
// Sessions and projects are fixed-layout tables (DESIGN.md section 7). Two properties, both readable
// from the markup a reader's browser gets:
//
//   THE COLGROUP FILLS THE TABLE. Its widths are percentages of a fixed-layout table and must sum
//   to 100, so no view ends early and leaves bare ground, and none overflows its board.
//
//   EVERY ROW PRINTS ONE CELL PER NAMED COLUMN, so field N lands under heading N on every row.
//
// THE DENOMINATOR IS THE MARKUP, not a list kept here: every assertion runs over the columns and
// rows the board actually rendered, and each block first asserts there is something to read.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { SessionRow } from '../src/entities/session';
import { DEFAULT_VIEWS, SessionsBoard } from '../src/pages/sessions';

/** The board view these tests are about: the lanes are the page's default now, the table a view. */
const BY_HEAD = DEFAULT_VIEWS.filter((view) => view.id === 'by-head')[0];
import { ProjectsBoard } from '../src/pages/projects';

const h = React.createElement;
const T0 = 1_700_000_000_000;

function session(over: Partial<SessionRow> = {}): SessionRow {
  return {
    pid: 100,
    session_id: 'sid-live',
    name: 'design-builder4',
    kind: 'interactive',
    version: '2.1.257',
    cwd: '/home/user/dev/atlas',
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

function project(over: Record<string, unknown> = {}) {
  return {
    id: '/dev/atlas',
    root: '/dev/atlas',
    live_sessions: 2,
    teams: 1,
    turns_today: 41,
    cost_today_usd: 12.84,
    day_start: 0,
    last_activity: T0,
    compaction: [],
    statusline_roots: [],
    ...over,
  };
}

// THE SESSIONS TABLE KEEPS THE RACK'S TWO PROPERTIES IN A TABLE'S TERMS. The budget becomes the
// colgroup: its widths are percentages of a fixed-layout table and must fill it exactly, so no view
// ends early and leaves bare ground, and none overflows its board. The grid becomes the row shape:
// every row prints one cell per named column.
describe('sessions declares its table', () => {
  const markup = () => renderToStaticMarkup(
    h(SessionsBoard, {
      view: BY_HEAD,
      payload: {
        note: 'headless `claude -p` runs never register',
        sessions: [session({ session_id: 'a' }), session({ session_id: 'b', name: 'splice-design' })],
      },
    }),
  );

  test('the colgroup fills the table exactly, and there is one to read', () => {
    const widths = [...markup().matchAll(/<col style="width:(\d+)%"/g)].map((m) => Number(m[1]));
    expect(widths.length).toBeGreaterThanOrEqual(2); // the denominator: no colgroup is not a pass
    expect(widths.reduce((sum, w) => sum + w, 0)).toBe(100);
  });

  test('every row prints one cell per named column', () => {
    const html = markup();
    const names = [...html.matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((m) => m[1]);
    const rows = html.split('</thead>')[1]?.split('<tr').slice(1).filter((row) => !row.includes('myx-dt-group')) ?? [];
    expect(rows.length).toBe(2);
    for (const row of rows) expect([...row.matchAll(/<td/g)].length).toBe(names.length);
  });
});

describe('projects declares its table', () => {
  const rows = [project(), project({ id: '/dev/relay', root: '/dev/relay', cost_today_usd: null, live_sessions: 0 })];
  const markup = () => renderToStaticMarkup(h(ProjectsBoard, { payload: { projects: rows } } as never));

  test('the colgroup fills the table exactly, and there is one to read', () => {
    const widths = [...markup().matchAll(/<col style="width:([\d.]+)%"/g)].map((m) => Number(m[1]));
    expect(widths.length).toBeGreaterThanOrEqual(2); // the denominator: no colgroup is not a pass
    expect(widths.reduce((sum, w) => sum + w, 0)).toBeCloseTo(100, 1);
  });

  test('every row prints one cell per named column, the repo first and its state last', () => {
    const html = markup();
    const names = [...html.matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((m) => m[1]);
    expect(names[0]).toBe('Repo');
    expect(names.at(-1)).toBe('State');
    const body = html.split('</thead>')[1]?.split('<tr').slice(1) ?? [];
    expect(body.length).toBe(2);
    for (const row of body) expect([...row.matchAll(/<td/g)].length).toBe(names.length);
  });

  // V4-269: the day's cost counted the REPOS it left out; a repo's dollars now leave out only the
  // turns no card priced, and those are what the figure counts.
  test("the day's cost is the priced dollars, and it counts the turns no card priced", () => {
    const counted = [
      project({ cost_today_usd: 2.5, unpriced_turns_today: 1 }),
      project({ id: '/dev/relay', root: '/dev/relay', cost_today_usd: 0.5, unpriced_turns_today: 0 }),
    ];
    const html = renderToStaticMarkup(h(ProjectsBoard, { payload: { projects: counted } } as never));
    expect(html).toContain('$3.00');
    expect(html).toContain('1 turn unpriced');
    expect(html).not.toContain('1 turns unpriced');
  });

  test('a repo with no rates prints the absence, and a quiet one says so', () => {
    const relay = markup().split('</thead>')[1]?.split('<tr').slice(1)[1] ?? '';
    expect(relay).toContain('>–<');
    expect(relay).toContain('>Quiet<');
  });

  // A dollar here is declared rates times tokens, never a bill, and the label's three words have no
  // room to say so: the kit's estimated basis says it beside the figure and the column.
  test('the day\'s API cost says it is an estimate, in the figure and the column', () => {
    const html = markup();
    expect(html).toMatch(/myx-stat-label">API cost today<span class="myx-basis">Estimated</);
    expect(html).toMatch(/>API cost today<span class="myx-basis">Estimated<\/span><\/th>/);
  });
});
