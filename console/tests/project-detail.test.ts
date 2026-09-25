// M4-05: the projects page reads GET /api/projects/{id} for the opened repository. The route
// answers ONE ProjectRow, the same row the list carries (ProjectsRoutes.project -> ProjectView.row),
// and a 404 naming the id for a root the daemon has not seen. Under test: the fields the detail
// prints from that row, its absences, and that a refusal reaches the page in the daemon's words.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchProject } from '../src/entities/project';
import type { ProjectRow } from '../src/entities/project';
import { projectStore } from '../src/entities/project/model/store';
import { H, S } from '../src/pages/projects/strings';
import {
  ProjectCompaction,
  ProjectStatusline,
  compactionViewOf,
  costText,
  dayText,
  detailRowsOf,
} from '../src/pages/projects/detail';

const h = React.createElement;
const NOW = Date.UTC(2026, 8, 23, 10, 0, 0);

/** The row the live daemon answered on 2026-09-23 for the e2e stack's repository. */
function row(over: Partial<ProjectRow> = {}): ProjectRow {
  return {
    id: '/tmp/console-e2e/e2e-repo',
    root: '/tmp/console-e2e/e2e-repo',
    live_sessions: 2,
    teams: 0,
    turns_today: 1,
    cost_today_usd: null,
    day_start: Date.UTC(2026, 8, 23),
    last_activity: NOW - 90_000,
    compaction: [],
    statusline_roots: [],
    ...over,
  };
}

describe('the project detail', () => {
  test('prints the counts and the day the daemon counted from', () => {
    expect(detailRowsOf(row(), NOW)).toEqual([
      ['Sessions running', '2'],
      ['Teams', '0'],
      ['Turns today', '1'],
      ['Cost today', '–'],
      ['Day', '2026-09-23 UTC'],
      ['Last seen', '1m ago'],
    ]);
  });

  test('a dollar figure from declared rates prints, and no rates or no activity is an absence', () => {
    const rows = new Map(detailRowsOf(row({ cost_today_usd: 12.844, last_activity: null }), NOW));
    expect(rows.get('Cost today')).toBe('$12.84');
    expect(rows.get('Last seen')).toBe('–');
    expect(costText(0), 'declared rates that came to nothing are a figure, not an absence').toBe('$0.00');
  });

  test('the day is the UTC day, never the reader\'s local midnight', () => {
    expect(dayText(Date.UTC(2026, 0, 1))).toBe('2026-01-01 UTC');
  });
});

// FEATURES.md 4.14's "compaction scope and effective instructions, the statusline roots entry",
// from the same row: three different compaction facts kept apart, and one statusline entry per head.
describe('what governs the repo', () => {
  const rule = { scope: 'project' as const, source: 'project:/tmp/console-e2e/e2e-repo', chars: 9 };

  test('the compaction field is three facts: rules, no rule, and a table never wired', () => {
    expect(compactionViewOf(row({ compaction: [rule] }))).toEqual({ kind: 'rules', rules: [rule] });
    expect(compactionViewOf(row({ compaction: [] }))).toEqual({ kind: 'client' });
    expect(compactionViewOf(row({ compaction: null }))).toEqual({ kind: 'unwired' });
  });

  test('the rules print as one table, their lengths in the compaction page\'s words', () => {
    const rules = [rule, { scope: 'model' as const, source: 'model:grok-4.3', chars: 0 }, { ...rule, source: 'x unreadable', chars: null }];
    const out = renderToStaticMarkup(h(ProjectCompaction, { row: row({ compaction: rules }) }));
    expect(out).toContain('aria-label="Compaction rules"');
    expect(out).toContain(`>${rule.source}<`);
    expect(out).toContain('>9<');
    expect(out).toContain('Client default');
    expect(out).toContain('Unavailable');
    expect(out, 'the project view has no per-head list for a rule').not.toContain('>Heads<');
  });

  test('no rule is the client instructions, and an unwired table says so rather than showing no rule', () => {
    const client = renderToStaticMarkup(h(ProjectCompaction, { row: row({ compaction: [] }) }));
    expect(client).toContain(S.noRule);
    const unwired = renderToStaticMarkup(h(ProjectCompaction, { row: row({ compaction: null }) }));
    expect(unwired).toContain(H.unwired);
    expect(unwired).not.toContain(S.noRule);
  });

  test('one statusline entry per head, and no branch where no trusted root covers the repo', () => {
    const roots = [
      { head: 'claudex', root: '/tmp', entry: 'tmp' as const },
      { head: 'grok', root: null, entry: null },
    ];
    const out = renderToStaticMarkup(h(ProjectStatusline, { row: row({ statusline_roots: roots }) }));
    const rows = out.split('<tbody')[1]?.split('<tr').slice(1) ?? [];
    expect(rows).toHaveLength(2);
    expect(rows[0]).toContain('claudex');
    expect(rows[0]).toContain('>/tmp<');
    expect(rows[0], 'the badge names the trusted entry that covers the repo').toContain('>Temp<');
    expect(rows[1]).toContain('grok');
    expect(rows[1]).toContain('>No branch<');
    expect(rows[1], 'a head with no root prints the absence, never a path').toContain('>–<');
  });

  test('no heads is one line and the way to add one', () => {
    const out = renderToStaticMarkup(h(ProjectStatusline, { row: row({ statusline_roots: [] }) }));
    expect(out).toContain(S.noHeads);
    expect(out).toContain('href="#/settings"');
  });
});

describe('the project detail read', () => {
  afterEach(() => vi.unstubAllGlobals());

  function stub(status: number, body: unknown): string[] {
    const urls: string[] = [];
    vi.stubGlobal('fetch', (input: unknown): Promise<Response> => {
      urls.push(String(input));
      return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }));
    });
    return urls;
  }

  test('reads the one project by its encoded root and holds its row', async () => {
    const urls = stub(200, row());
    await fetchProject(row().id);
    expect(urls).toEqual([`/api/projects/${encodeURIComponent(row().id)}`]);
    expect(projectStore.get().data).toEqual(row());
  });

  test('a root the daemon has not seen is its 404 sentence, never a pending row', async () => {
    stub(404, { error: 'not a project root splice has seen: /nope' });
    await fetchProject('/nope');
    expect(projectStore.get().error).toBe('not a project root splice has seen: /nope');
  });
});
