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
import {
  CLIENT_OWN,
  COMPACTION_UNWIRED,
  ProjectCompaction,
  ProjectDetail,
  ProjectStatusline,
  compactionViewOf,
  dayText,
  detailFieldsOf,
  statuslineFieldsOf,
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

const values = (fields: { label: string; value: string; basis?: string | undefined }[]) =>
  fields.map((field) => [field.label, field.value, field.basis ?? null]);

describe('the project detail', () => {
  test('prints the counts and the day the daemon counted from', () => {
    const fields = detailFieldsOf(row(), NOW);
    expect(values(fields.counts)).toEqual([
      ['live sessions', '2', 'measured'],
      ['teams', '0', 'measured'],
      ['turns today', '1', 'measured'],
    ]);
    expect(values(fields.today)).toEqual([
      ['cost today', 'n/r', null],
      ['day start', '2026-09-23 UTC', 'measured'],
      ['last seen', '1m ago', 'measured'],
    ]);
  });

  test('a dollar figure from declared rates is an estimate, and no activity is an absence', () => {
    const fields = detailFieldsOf(row({ cost_today_usd: 12.844, last_activity: null }), NOW);
    expect(values(fields.today)).toEqual([
      ['cost today', '$12.84', 'estimated'],
      ['day start', '2026-09-23 UTC', 'measured'],
      ['last seen', 'n/r', null],
    ]);
  });

  test('the day is the UTC day, never the reader\'s local midnight', () => {
    expect(dayText(Date.UTC(2026, 0, 1))).toBe('2026-01-01 UTC');
  });

  test('a sample row renders as the detail with nothing read', () => {
    const out = renderToStaticMarkup(h(ProjectDetail, { id: row().id, row: row() }));
    expect(out).toContain('live sessions');
    expect(out).toContain('2026-09-23 UTC');
    expect(out).toContain(`aria-label="activity ${row().root}"`);
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

  test('each rule prints as the compaction page prints it, its length in the same words', () => {
    const rules = [rule, { scope: 'model' as const, source: 'model:grok-4.3', chars: 0 }, { ...rule, source: 'x unreadable', chars: null }];
    const out = renderToStaticMarkup(h(ProjectCompaction, { id: row().id, row: row({ compaction: rules }) }));
    expect(out).toContain(`aria-label="instruction ${rule.source}"`);
    expect(out).toContain('>9<');
    expect(out).toContain('opt-out');
    expect(out).toContain('unavailable');
    expect(out, 'the project view has no per-head list for a rule').not.toContain('heads');
  });

  test('no rule is the client instructions, and an unwired table says so rather than showing no rule', () => {
    const client = renderToStaticMarkup(h(ProjectCompaction, { id: row().id, row: row({ compaction: [] }) }));
    expect(client).toContain(CLIENT_OWN.text);
    const unwired = renderToStaticMarkup(h(ProjectCompaction, { id: row().id, row: row({ compaction: null }) }));
    expect(unwired).toContain(COMPACTION_UNWIRED);
    expect(unwired).not.toContain(CLIENT_OWN.text);
  });

  test('one statusline entry per head, and none where no trusted root covers the repo', () => {
    const roots = [
      { head: 'claudex', root: '/tmp', entry: 'tmp' as const },
      { head: 'grok', root: null, entry: null },
    ];
    expect(statuslineFieldsOf(row({ statusline_roots: roots }))).toEqual([
      { head: 'claudex', root: '/tmp', entry: 'tmp' },
      { head: 'grok', root: 'none', entry: 'none' },
    ]);
    const out = renderToStaticMarkup(h(ProjectStatusline, { id: row().id, row: row({ statusline_roots: roots }) }));
    expect(out).toContain('aria-label="statusline roots claudex"');
    expect(out).toContain('trusted');
    expect(out).toContain('no branch');
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
