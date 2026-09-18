// M2-31: THE DECLARED ch GRID OF THE TWO RACK PAGES, READ OFF THE MARKUP.
//
// This file exists because the row's own verify line named it and it was not there: a
// `vitest run tests/sessions.test.ts tests/projects.test.ts tests/detail-rest.test.ts` exits 0
// having run TWO files, because vitest treats a named path that matches nothing as nothing to do.
// A green suite says the code ran (campaign law), and that one did not.
//
// What it guards is the thing M2-31 changed and nothing else pins: the per-column ch declarations.
// A strip's rendered width is `width: <w>ch` with `flex-grow: <w>` (shared/ui/strip-field.tsx), so
// the declared numbers ARE the layout -- the share each column takes of the rack, and the floor the
// strip scrolls to when its bay is too narrow to grow into. Three properties, all three readable
// from the markup a reader's browser gets:
//
//   THE BUDGET STAYS. The totals are 122ch (sessions) and 102ch (projects), unchanged by M2-31's
//   redistribution and deliberately so: fitting every column to its content does not tighten the
//   rack, it ends the rack early and leaves bare ground to the bay edge, which is the defect M2-29
//   measured at 453px on fleet. A future edit that shrinks a rack to its content trips this.
//
//   THE GRID IS A GRID. Every strip in a rack declares the same widths in the same order, so
//   field N lands at the same x on every row (the M1-107 property).
//
//   NO COLUMN IS NARROWER THAN ITS OWN NAME. StripField clips rather than wraps, so a column
//   declaring fewer ch than its label has characters prints a truncated heading -- a page that
//   cannot say what it is showing.
//
// THE DENOMINATOR IS THE MARKUP, not a list kept here: `gridOf` enumerates every field the board
// actually rendered and every assertion runs over that, so a column added to a page without a
// width, or dropped from one, is counted by this file rather than missed by it (section 24).
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { SessionRow } from '../src/entities/session';
import { SessionsBoard } from '../src/pages/sessions';
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
    cwd: '/home/marcos/dev/mythos',
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
    id: '/dev/mythos',
    root: '/dev/mythos',
    live_sessions: 2,
    teams: 1,
    turns_today: 41,
    cost_today_usd: 12.84,
    day_start: 0,
    last_activity: T0,
    ...over,
  };
}

interface Cell {
  label: string;
  w: number;
}

/**
 * Every strip in a rendered board, as the list of fields it declares. Read out of the markup with
 * one pass per strip so a field missing its width, or a strip with a different field count, shows
 * up as a difference between rows rather than being quietly averaged away.
 */
function gridOf(markup: string): Cell[][] {
  const strips = markup.split('class="myx-strip').slice(1);
  return strips.map((strip) => {
    const cells: Cell[] = [];
    const field = /myx-sfield" style="width:(\d+)ch[^"]*"><span class="myx-sfield-label">([^<]*)</g;
    let hit = field.exec(strip);
    while (hit !== null) {
      cells.push({ w: Number(hit[1]), label: hit[2] });
      hit = field.exec(strip);
    }
    return cells;
  }).filter((cells) => cells.length > 0);
}

const RACKS: { page: string; budget: number; markup: () => string }[] = [
  {
    page: 'sessions',
    budget: 122,
    markup: () =>
      renderToStaticMarkup(
        h(SessionsBoard, {
          payload: {
            note: 'headless `claude -p` runs never register',
            sessions: [session({ session_id: 'a' }), session({ session_id: 'b', name: 'splice-design' })],
          },
        }),
      ),
  },
  {
    page: 'projects',
    budget: 102,
    markup: () =>
      renderToStaticMarkup(
        h(ProjectsBoard, {
          payload: { projects: [project(), project({ id: '/dev/qgre', root: '/dev/qgre', cost_today_usd: null })] },
        } as never),
      ),
  },
];

describe.each(RACKS)('$page declares its rack', ({ page, budget, markup }) => {
  test('renders a rack of strips with fields to measure', () => {
    const grid = gridOf(markup());
    // Law 23: this assertion is here so the three below cannot pass by finding nothing. A board
    // that rendered an empty, a fault or a pending route has no fields, and every `every()` over
    // an empty list is true.
    expect(grid.length).toBeGreaterThanOrEqual(2);
    expect(grid[0].length).toBeGreaterThanOrEqual(2);
  });

  test(`the budget stays at ${budget}ch on every strip`, () => {
    for (const strip of gridOf(markup())) {
      expect(strip.reduce((sum, cell) => sum + cell.w, 0)).toBe(budget);
    }
  });

  test('every strip declares the same columns in the same order', () => {
    const grid = gridOf(markup());
    const first = JSON.stringify(grid[0]);
    for (const strip of grid) expect(JSON.stringify(strip)).toBe(first);
  });

  test('no column is narrower than the name it prints', () => {
    // Asserted as a list rather than a loop of booleans so a failure names the column and the two
    // numbers, which is the whole difference between a red test and a useful one.
    const tight = gridOf(markup())[0].filter((cell) => cell.w < cell.label.length);
    expect({ page, tight }).toEqual({ page, tight: [] });
  });
});
