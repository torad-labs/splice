// V4-420: Fleet's Last turn column reads the dash every other empty cell uses for a head that has
// never run a turn (Marlin, f7f1e9308: claude-bonsai-second read "None" while its neighbours read a
// dash or "2d ago"). What is pinned: the cell a head with no turn behind it renders, the cell a head
// the perf summary does not name renders, and the cell of a head with a newest turn, read by the
// column's header so a reordered column cannot move the assertion.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { parseFragment, type DefaultTreeAdapterTypes } from 'parse5';
import { FleetBoard } from '../src/pages/fleet';
import type { FleetSources } from '../src/pages/fleet';
import type { HeadStatus } from '../src/shared/api';
import { ABSENT, timeAgo } from '../src/shared/lib';

const h = React.createElement;

const NOW = 1_800_000_000_000;
const TWO_DAYS_AGO = NOW - 2 * 24 * 60 * 60 * 1000;

function head(key: string): HeadStatus {
  return {
    key,
    label: key,
    name: key,
    port: 3099,
    authKind: 'chatgpt-oauth',
    wantVersion: '0.4.0',
    running: true,
    healthy: true,
    version: '0.4.0',
    versionMatch: true,
    mode: null,
    gate: { inflight: 0, queued: 0, max: 4, acquired: 0, released: 0, waited: 0, avg_wait_ms: 0, live: [], stream_idle_ms: 30000 },
    maxInflight: 4,
    health: { localOriginErrors: 0, providerErrors: 0 },
    pids: [1],
  };
}

/** `never` ran no turn (the perf summary names it with a null newest turn), `recent` ran one two days
 *  ago, and `silent` is a head the perf summary does not name at all. */
const SOURCES: FleetSources = {
  auth: null,
  usage: null,
  accounts: null,
  topology: null,
  catalogs: null,
  fieldsPending: false,
  topologyStale: false,
  landed: [],
  lastTs: new Map<string, number | null>([['never', null], ['recent', TWO_DAYS_AGO]]),
  overrides: [],
};

function nodeText(node: DefaultTreeAdapterTypes.Node): string {
  if (node.nodeName === '#text' && 'value' in node) return node.value;
  return 'childNodes' in node ? node.childNodes.map(nodeText).join(' ') : '';
}

const text = (cell: string): string => nodeText(parseFragment(cell)).trim();

/** Each row's Last turn cell, by head key, read at the position of that column's header. */
function lastTurnCells(html: string): Record<string, string> {
  const headers = [...html.matchAll(/<th[^>]*>(.*?)<\/th>/g)].map((match) => text(match[1] ?? ''));
  const column = headers.indexOf('Last turn');
  expect(column, `Fleet has a Last turn column: ${headers.join(', ')}`).toBeGreaterThanOrEqual(0);
  const body = html.slice(html.indexOf('<tbody'));
  const cells: Record<string, string> = {};
  for (const row of body.split('<tr').slice(1)) {
    const tds = [...row.matchAll(/<td[^>]*>(.*?)<\/td>/g)].map((match) => match[1] ?? '');
    const opener = /aria-label="Open plan ([^"]+)"/.exec(row);
    cells[opener?.[1] ?? `row ${Object.keys(cells).length}`] = text(tds[column] ?? '');
  }
  return cells;
}

const board = (): string =>
  renderToStaticMarkup(h(FleetBoard, {
    heads: [head('never'), head('recent'), head('silent')],
    sources: SOURCES,
    openKey: null,
    onOpen: () => undefined,
    nowMs: NOW,
  }));

describe('Fleet\'s Last turn cell (V4-420)', () => {
  test('a head that never ran a turn reads the dash, never None', () => {
    const cells = lastTurnCells(board());
    expect(cells.never).toBe(ABSENT);
  });

  test('a head the perf summary does not name reads the same dash', () => {
    expect(lastTurnCells(board()).silent).toBe(ABSENT);
  });

  test('a head with a newest turn reads how long ago it was', () => {
    expect(lastTurnCells(board()).recent).toBe(timeAgo(TWO_DAYS_AGO, NOW));
  });

  test('no cell on the board prints None, null or undefined', () => {
    // Parsed nodes stay separated, so adjacent cells cannot hide a word from the boundary assertion.
    const words = text(board());
    expect(words).not.toMatch(/\bNone\b|\bnull\b|\bundefined\b/);
  });
});
