import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { deleteKept, fetchKept, fetchTraceKept, removeKept, removeTraceKept } from '../src/entities/kept';
import { keptRows } from '../src/pages/kept/model';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { KeptBoard, switchOf } from '../src/pages/kept';
import { H, S } from '../src/pages/kept/strings';
import { ConfirmKeys } from '../src/shared/controls/confirm';

const source = readFileSync(fileURLToPath(new URL('../../core/src/main/kotlin/splice/core/config/Knob.kt', import.meta.url)), 'utf8');
function recordingKnobs(sourceText: string): string[] {
  return [...sourceText.matchAll(/^ {4}([A-Z_]+)\(/gm)]
    .map((match) => match[1])
    .filter((name) => /^(?:ACTIVITY_|PERF_ARCHIVE_|TRACE(?:_|$)|MESSAGE_EDGES$|WIRE_TAP$|TRANSCRIPT_VIEW$)/.test(name));
}

function unlisted(sourceText: string): string[] {
  const covered = new Set(keptRows.flatMap((row) => row.knobs));
  return recordingKnobs(sourceText).filter((name) => !covered.has(name));
}

describe('what splice keeps', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('edge and label inventories read and delete through their own guarded route', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`);
      return { ok: true, status: 200, json: async () => ({
        store: url.endsWith('/labels') ? 'labels' : 'edges', state: 'on', days: 2, rows: 3,
        oldest: '2026-09-26', ages_out: '2026-12-26',
      }) };
    });
    expect((await fetchKept('edges')).rows).toBe(3);
    expect((await deleteKept('labels')).store).toBe('labels');
    expect(calls).toEqual(['GET /api/kept/edges', 'DELETE /api/kept/labels']);
  });

  test('every conversation store declared in Knob has a named disposition', () => {
    const covered = keptRows.flatMap((row) => row.knobs);
    expect(recordingKnobs(source)).toContain('PERF_ARCHIVE_RETENTION_DAYS');
    expect(new Set(covered).size).toBe(covered.length);
    expect(covered.sort()).toEqual(recordingKnobs(source).sort());
    expect(unlisted(source)).toEqual([]);
    expect(keptRows.map((row) => row.id)).toEqual(['edges', 'labels', 'trace', 'wire-tap', 'transcripts', 'turns']);
    for (const row of keptRows) {
      expect(row.holds).not.toBe('');
      expect(row.window).not.toBe('');
      expect(row.location).not.toBe('');
      expect(row.switch).not.toBe('');
    }
  });

  test('turn statistics use their own guarded inventory and a post-delete census', async () => {
    const calls: string[] = [];
    let deleted = false;
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push(`${method} ${url}`);
      if (method === 'DELETE') deleted = true;
      return { ok: true, status: 200, json: async () => ({
        store: 'turns', state: deleted ? 'deleted' : 'on',
        days: deleted ? 0 : 2, rows: deleted ? 0 : 7,
        oldest: deleted ? null : '2026-09-26', ages_out: null,
      }) };
    });
    expect((await fetchKept('turns')).rows).toBe(7);
    const remaining = await removeKept('turns');
    expect(calls).toEqual(['GET /api/kept/turns', 'DELETE /api/kept/turns', 'GET /api/kept/turns']);
    expect(remaining.inventory).toMatchObject({ store: 'turns', state: 'deleted', days: 0, rows: 0 });
  });

  test('the age-out date belongs to the last kept day, not the oldest', () => {
    expect(H.agesOut('Dec 26')).toBe('The last kept day ages out on Dec 26.');
  });

  test('every available switch writes the owning knob, while head-only controls are not faked', () => {
    const pick = (id: string) => {
      const row = keptRows.find((candidate) => candidate.id === id);
      if (row === undefined) throw new Error(`missing ${id} store row`);
      return switchOf(row);
    };
    expect(pick('edges')).toMatchObject({ key: 'messageEdges', off: false, enabled: true, restart: true });
    expect(pick('labels')).toBeNull();
    expect(pick('trace')).toBeNull();
    expect(pick('wire-tap')).toBeNull();
    expect(pick('transcripts')).toMatchObject({ key: 'transcriptView', off: false, enabled: true, restart: false });
  });

  test('delete returns removed counts; the page reads the remaining census after the write', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push(`${method} ${url}`);
      return { ok: true, status: 200, json: async () => ({
        store: 'edges', state: 'deleted',
        days: method === 'DELETE' ? 2 : 0, rows: method === 'DELETE' ? 3 : 0,
        oldest: method === 'DELETE' ? '2026-09-26' : null, ages_out: method === 'DELETE' ? '2026-12-26' : null,
      }) };
    });
    const remaining = await removeKept('edges');
    expect(calls).toEqual(['DELETE /api/kept/edges', 'GET /api/kept/edges']);
    expect(remaining.inventory).toMatchObject({ days: 0, rows: 0, state: 'deleted' });
    expect(remaining).toMatchObject({ deleted: true, deleteError: null, readError: null });
  });

  test('a partial deletion failure rereads remaining days rather than retaining stale counts', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push(`${method} ${url}`);
      return method === 'DELETE'
        ? { ok: false, status: 500, json: async () => ({ error: 'one day could not be deleted' }) }
        : { ok: true, status: 200, json: async () => ({ store: 'edges', state: 'on', days: 1, rows: 1, oldest: '2026-09-27', ages_out: '2026-12-26' }) };
    });
    const result = await removeKept('edges');
    expect(result.inventory?.rows).toBe(1);
    expect(result.deleted).toBe(false);
    expect(result.deleteError).toContain('one day could not be deleted');
    expect(result.readError).toBeNull();
    expect(calls).toEqual(['DELETE /api/kept/edges', 'GET /api/kept/edges']);
  });

  test('a successful delete followed by an unreadable census says deleted but unknown', async () => {
    let reads = 0;
    vi.stubGlobal('fetch', async (_url: string, init?: RequestInit) => {
      if (init?.method === 'DELETE') return { ok: true, status: 200, json: async () => ({ store: 'edges', state: 'deleted', days: 2, rows: 3, oldest: '2026-09-26', ages_out: '2026-12-26' }) };
      reads += 1;
      return { ok: false, status: 503, json: async () => ({ error: 'store cannot be read' }) };
    });
    const result = await removeKept('edges');
    expect(reads).toBe(1);
    expect(result.inventory).toBeNull();
    expect(result.deleted).toBe(true);
    expect(result.deleteError).toBeNull();
    expect(result.readError).toContain('store cannot be read');
  });

  test('per-head trace reads and deletes its own route, then shows the after-delete count', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push(`${method} ${url}`);
      return { ok: true, status: 200, json: async () => ({ head: 'local', state: 'deleted', days: method === 'DELETE' ? 2 : 0,
        records: method === 'DELETE' ? 3 : 0, bytes: method === 'DELETE' ? 64 : 0,
        oldest: method === 'DELETE' ? '2026-09-26' : null, ages_out: method === 'DELETE' ? '2026-10-02' : null }) };
    });
    expect((await fetchTraceKept('local')).head).toBe('local');
    expect((await removeTraceKept('local')).inventory?.records).toBe(0);
    expect(calls).toEqual([
      'GET /api/heads/local/trace/kept', 'DELETE /api/heads/local/trace/kept', 'GET /api/heads/local/trace/kept',
    ]);
  });

  test('per-head captured bodies show their real inventory and a counted delete action', () => {
    const html = renderToStaticMarkup(createElement(KeptBoard, {
      inventories: {}, heads: ['local'],
      traces: { local: { head: 'local', state: 'kept', days: 2, records: 3, bytes: 64,
        oldest: '2026-09-26', ages_out: '2026-10-02' } },
      onDelete: () => undefined, onDeleteTrace: () => undefined,
    }));
    expect(html).toContain('Capture on local');
    expect(html).toContain('3 records');
    expect(html).toContain('Oct 2');
    expect(html).toContain('Delete what&#x27;s kept');
  });

  test('current and archived turns show their true count, expiry and Delete on this page', () => {
    const html = renderToStaticMarkup(createElement(KeptBoard, {
      inventories: { turns: { store: 'turns', state: 'on', days: 2, rows: 7,
        oldest: '2026-09-26', ages_out: null } },
      onDelete: () => undefined, onSwitch: () => undefined,
      effective: { perfArchiveRetentionDays: 90 },
    }));
    const row = html.slice(html.indexOf('Turn statistics'));
    expect(row).toContain('7 entries kept');
    expect(row).toContain('Current turn files have no automatic expiry.');
    expect(row).toContain('Old archives clear after the chosen days, at the next rotation.');
    expect(row).not.toContain('ages out on');
    expect(row).toContain('Delete what&#x27;s kept');
    expect(row).toContain('Delete removes every recorded turn time, model and session.');
    expect(row).toContain('The Turns timeline and per-turn costs restart with the next turn.');
    expect(row).toContain('Open sessions start counting cost from zero after Delete.');
    expect(row).toContain('session-totals.json');
    expect(row).toContain('Archive days');
    expect(row).toContain('Stop archiving');
  });

  test('held edges and labels show real counts, expiry, switches and counted delete controls', () => {
    const html = renderToStaticMarkup(createElement(KeptBoard, {
      inventories: {
        edges: { store: 'edges', state: 'on', days: 2, rows: 3, oldest: '2026-09-26', ages_out: '2026-12-26' },
        labels: { store: 'labels', state: 'off', reason: 'labels off', days: 1, rows: 1, oldest: '2026-09-27', ages_out: '2026-09-29' },
      },
      onDelete: () => undefined,
    }));
    expect(html).toContain('Message edges');
    expect(html).toContain('Activity labels');
    expect(html).toContain('Dec 26');
    expect(html).toContain('Sep 29');
    expect(html).toContain('Delete what&#x27;s kept');
    const confirm = (days: number, entries: number) => renderToStaticMarkup(createElement(ConfirmKeys, {
      label: S.deleteKept, confirmLabel: S.deleteCount(days, entries), armed: true,
      onArm: () => undefined, onConfirm: () => undefined, onCancel: () => undefined,
    }));
    expect(confirm(2, 3)).toContain('Delete 2 days and 3 entries');
    expect(confirm(1, 1)).toContain('Delete 1 day and 1 entry');
    expect(html).toContain('Restart to apply');
    const custom = renderToStaticMarkup(createElement(KeptBoard, {
      inventories: {}, effective: { messageEdges: true, activityStoreHeads: 'local', transcriptView: true },
      onDelete: () => undefined, onSwitch: () => undefined,
    }));
    expect(custom).toContain('Activity plans');
    expect(custom).toContain('value="local"');
    expect(custom).not.toContain('value="*"');
    expect(html).toContain('Request capture');
    expect(html).toContain('Transcript view');
    expect(html).not.toContain('Everything is empty');
  });

  test('an empty unlisted recording knob fails the source comparison by name', () => {
    const marker = '    BUDGET_DEFAULT_ACTION(';
    expect(source).toContain(marker);
    const mutated = source.replace(marker, '    PERF_ARCHIVE_EMPTY("perfArchiveEmpty", KnobKind.NUMBER, listOf(), 0L),\n' + marker);
    expect(unlisted(mutated)).toContain('PERF_ARCHIVE_EMPTY');
  });
});
