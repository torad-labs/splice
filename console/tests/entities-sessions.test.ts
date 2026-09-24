// M2-D3: the data behind the sessions, projects, transcript, mcp, doctor, budget and alert
// entities. Derivations and stores are tested directly (CONTRACTS.md 4); the API-level cases stub
// global fetch, because the pending state a page renders is a property of the response handling:
// "not built yet" and "the request failed" must not arrive at the store in the same shape.
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { SessionRow } from '../src/entities/session';
import {
  availabilityCounts,
  fetchSessions,
  groupSessions,
  timeline,
  UNKNOWN_HEAD,
  UNATTRIBUTED,
} from '../src/entities/session';
import { sessionRegistryStore } from '../src/entities/session/model/store';
import { advanceCursor, loadTranscript, openCursor, PENDING_TRANSCRIPT } from '../src/entities/transcript';
import { transcriptStore } from '../src/entities/transcript/model/store';
import type { TranscriptPage } from '../src/entities/transcript';
import { checkFix, checkSection, fetchDoctor, isRedacted, leaksIn, leaksInText } from '../src/entities/doctor';
import { doctorStore } from '../src/entities/doctor/model/store';
import { fetchProjects } from '../src/entities/project';
import { projectsStore } from '../src/entities/project/model/store';
import { fetchBudgets } from '../src/entities/budget';
import { budgetsStore } from '../src/entities/budget/model/store';
import { fetchAlerts, sendTestAlert } from '../src/entities/alert';
import { alertsStore } from '../src/entities/alert/model/store';
import { fetchMcp } from '../src/entities/mcp';
import { mcpStore } from '../src/entities/mcp/model/store';

// ── fixtures ─────────────────────────────────────────────────────────────────

const HOUR = 3_600_000;
const T0 = 1_700_000_000_000;

function session(over: Partial<SessionRow> = {}): SessionRow {
  return {
    pid: 100,
    session_id: 'sid',
    name: 'sess',
    kind: 'interactive',
    version: '2.1.257',
    cwd: '/home/user/dev/a',
    status: 'busy',
    status_updated_at: T0,
    started_at: T0,
    updated_at: T0,
    address: null,
    head: 'claudex',
    availability: 'live',
    ...over,
  };
}

function page(over: Partial<TranscriptPage> = {}): TranscriptPage {
  return { session_id: 's1', path: '/home/user/.claude/projects/x/s1.jsonl', messages: [], ...over };
}

/** Routes not named here answer 404, the way an unbuilt daemon route does. */
function stubRoutes(routes: Record<string, unknown>): void {
  vi.stubGlobal('fetch', (input: unknown): Promise<Response> => {
    const path = String(input).split('?')[0];
    if (!(path in routes)) return Promise.resolve(new Response('not found', { status: 404 }));
    return Promise.resolve(
      new Response(JSON.stringify(routes[path]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

// ── grouping ─────────────────────────────────────────────────────────────────

describe('groupSessions', () => {
  const rows: SessionRow[] = [
    session({ head: 'claudex' }),
    session({ head: 'claudex' }),
    session({ head: UNKNOWN_HEAD }),
    session({ head: 'claude-grok' }),
  ];

  test('files by head and keeps unknown-head sessions in the total', () => {
    const groups = groupSessions(rows, 'head');
    expect(groups.map((g) => [g.key, g.count])).toEqual([
      ['claudex', 2],
      ['claude-grok', 1],
      [UNKNOWN_HEAD, 1],
    ]);
    expect(groups.reduce((n, g) => n + g.count, 0)).toBe(rows.length);
  });

  test('files by repo, falling back to the cwd until the resolver lands', () => {
    const groups = groupSessions(
      [
        session({ repo: { root: '/dev/atlas' } }),
        session({ repo: { root: '/dev/atlas', worktree: '/dev/atlas/.claude/worktrees/v0.4.0' } }),
        session({ cwd: '/dev/solo' }), // no repo field yet (V4-130)
        session({ cwd: null }), // neither
      ],
      'repo',
    );
    expect(groups.map((g) => [g.key, g.count])).toEqual([
      ['/dev/atlas', 2],
      ['/dev/solo', 1],
      [UNATTRIBUTED, 1],
    ]);
  });

  test('files by team, which is unattributed for every row until teams exist', () => {
    expect(groupSessions(rows, 'team').map((g) => g.key)).toEqual([UNATTRIBUTED]);
    expect(groupSessions([...rows, session({ team: 'web-console' })], 'team').map((g) => [g.key, g.count])).toEqual([
      [UNATTRIBUTED, 4],
      ['web-console', 1],
    ]);
  });

  test('counts the three availability states', () => {
    expect(availabilityCounts([session(), session({ availability: 'stale' }), session({ availability: 'gone' }), session({ availability: 'gone' })])).toEqual(
      { live: 1, stale: 1, gone: 2 },
    );
  });
});

// ── timeline ─────────────────────────────────────────────────────────────────

describe('timeline', () => {
  test('buckets by start time and keeps idle buckets as gaps', () => {
    const result = timeline(
      [session({ started_at: T0 + 60_000 }), session({ started_at: T0 + HOUR + 60_000 })],
      { from: T0, to: T0 + 3 * HOUR, bucketMs: HOUR },
    );
    expect(result.buckets.map((b) => b.sessions.length)).toEqual([1, 1, 0]);
    expect(result.buckets.map((b) => [b.start, b.end])).toEqual([
      [T0, T0 + HOUR],
      [T0 + HOUR, T0 + 2 * HOUR],
      [T0 + 2 * HOUR, T0 + 3 * HOUR],
    ]);
    expect(result.undated).toEqual([]);
  });

  test('reports a row with no timestamp instead of dropping it', () => {
    const orphan = session({ started_at: null });
    const result = timeline([orphan], { from: T0, to: T0 + HOUR, bucketMs: HOUR });
    expect(result.buckets[0].sessions).toEqual([]);
    expect(result.undated).toEqual([orphan]);
  });

  test('the window is half-open: a row outside it is neither bucketed nor undated', () => {
    const result = timeline([session({ started_at: T0 - 1 }), session({ started_at: T0 + HOUR })], {
      from: T0,
      to: T0 + HOUR,
      bucketMs: HOUR,
    });
    expect(result.buckets.flatMap((b) => b.sessions)).toEqual([]);
    expect(result.undated).toEqual([]);
  });

  test('uses the last-heard time when asked for it', () => {
    const row = session({ started_at: null, updated_at: T0 + HOUR + 1 });
    const result = timeline([row], { from: T0, to: T0 + 2 * HOUR, bucketMs: HOUR, by: 'updated_at' });
    expect(result.buckets[1].sessions).toEqual([row]);
    expect(result.undated).toEqual([]);
  });

  test('refuses a bucket width that cannot bucket', () => {
    expect(() => timeline([], { from: T0, to: T0 + HOUR, bucketMs: 0 })).toThrow(/positive bucketMs/);
  });
});

// ── transcript cursor ────────────────────────────────────────────────────────

describe('transcript cursor', () => {
  test('opens at the beginning and walks forward page by page', () => {
    const open = openCursor('s1');
    expect(open).toEqual({ sessionId: 's1', next: null, pages: 0, complete: false });

    const first = advanceCursor(open, page({ next: 'tok-2' }));
    expect(first.reset).toBe(false);
    expect(first.cursor).toEqual({ sessionId: 's1', next: 'tok-2', pages: 1, complete: false });

    const second = advanceCursor(first.cursor, page({ next: 'tok-3' }));
    expect(second.cursor).toEqual({ sessionId: 's1', next: 'tok-3', pages: 2, complete: false });
  });

  test('a page with no next token ends the read, whether absent or null', () => {
    expect(advanceCursor(openCursor('s1'), page()).cursor.complete).toBe(true);
    expect(advanceCursor(openCursor('s1'), page({ next: null })).cursor.complete).toBe(true);
  });

  test('a page from another session resets instead of interleaving two conversations', () => {
    const walked = advanceCursor(openCursor('s1'), page({ next: 'tok-2' })).cursor;
    const other = advanceCursor(walked, page({ session_id: 's2' }));
    expect(other.reset).toBe(true);
    expect(other.cursor).toEqual({ sessionId: 's2', next: null, pages: 1, complete: true });
  });
});

// ── pending routes ───────────────────────────────────────────────────────────

describe('pending routes', () => {
  test('each unbuilt route resolves its store to the item that will serve it', async () => {
    stubRoutes({});
    await loadTranscript('s1');
    expect(transcriptStore.get().data).toEqual({ pending: PENDING_TRANSCRIPT });
    await fetchDoctor();
    expect(doctorStore.get().data).toEqual({ pending: 'V4-127' });
    await fetchBudgets();
    expect(budgetsStore.get().data).toEqual({ pending: 'V4-133' });
    await fetchAlerts();
    expect(alertsStore.get().data).toEqual({ pending: 'V4-133' });
  });

  test('a test send reports failure rather than a fake delivery', async () => {
    stubRoutes({});
    expect(await sendTestAlert()).toBe(false);
  });

  test('a route that EXISTS reports a 404 as an error, not as pending', async () => {
    // /api/sessions and /api/mcp are on the daemon today, so their 404 is a real failure and the
    // page must say so rather than print a pending empty forever.
    stubRoutes({});
    await fetchSessions();
    expect(sessionRegistryStore.get().error).toBe('HTTP 404');
    expect(sessionRegistryStore.get().data).toBeNull();
    await fetchMcp();
    expect(mcpStore.get().error).toBe('HTTP 404');
    expect(mcpStore.get().data).toBeNull();
    // Served since V4-131 (ProjectsRoutes.list), so a 404 is a failure, never the pending row.
    await fetchProjects();
    expect(projectsStore.get().error).toBe('HTTP 404');
    expect(projectsStore.get().data).toBeNull();
  });

  test('the live routes land their payloads', async () => {
    const row = session();
    stubRoutes({
      '/api/sessions': { note: 'headless `claude -p` runs never register', sessions: [row] },
      '/api/mcp': { hosting: true, servers: { fs: { eligible: true, hosted: false, sessions: 0, session_ids: [], streams: 0, restarts: 3 } } },
    });
    await fetchSessions();
    expect(sessionRegistryStore.get().data?.sessions).toEqual([row]);
    await fetchMcp();
    const mcp = mcpStore.get().data;
    expect(mcp?.hosting).toBe(true);
    expect(mcp?.servers.fs).toMatchObject({ hosted: false, restarts: 3 });
  });
});

// ── doctor ───────────────────────────────────────────────────────────────────

describe('doctor', () => {
  const EM = String.fromCharCode(0x2014);

  test('reads the section and the remedy the report buries in the detail', () => {
    expect(checkSection({ id: 'daemon/port', status: 'ok', detail: 'x' })).toBe('daemon');
    expect(checkSection({ id: 'bare', status: 'ok', detail: 'x' })).toBe('bare');
    expect(checkFix({ id: 'a/b', status: 'fail', detail: `no daemon ${EM} fix: splice restart` })).toBe('splice restart');
    expect(checkFix({ id: 'a/b', status: 'ok', detail: 'all good' })).toBeNull();
  });

  test('a clean report passes and a credential shape fails, by path and never by value', () => {
    expect(isRedacted({ checks: [{ id: 'a/b', status: 'ok', detail: 'nothing to report' }] })).toBe(true);
    const leaks = leaksIn({ checks: [{ id: 'a/b', status: 'warn', detail: 'token=abc123' }] });
    expect(leaks).toEqual([{ kind: 'key-value', where: 'checks[0].detail' }]);
    expect(JSON.stringify(leaks)).not.toContain('abc123');
  });

  test('recognizes the shapes the CLI masks', () => {
    expect(leaksInText('sk-abcdefghijklmnop')).toEqual(['provider-key']);
    expect(leaksInText('person@example.com')).toEqual(['email']);
    expect(leaksInText('4e22c196-64b7-4a64-9972-5ddd337dec15')).toEqual(['uuid']);
    expect(leaksInText('all quiet')).toEqual([]);
    expect(leaksInText(`Authorization: Bearer ${'a'.repeat(50)}`)).toContain('bearer');
    expect(leaksInText(`Authorization: Bearer ${'a'.repeat(50)}`)).toContain('opaque');
  });
});
