// M2-02: the sessions and projects pages. The view selection is a pure module
// and is tested directly; the boards and widgets are rendered to static markup
// (CONTRACTS.md section 4: a .ts test cannot hold JSX) and asserted on what a
// reader can actually see, because three of this world's rules are only true if
// the MARKUP says so:
//   - the availability state is printed, not only coloured, so a grayscale
//     screenshot still says which session stopped reporting;
//   - a pending route renders the honest empty NAMING the row that will serve
//     it, and a transcript or file body is not in the DOM until asked for;
//   - a value the daemon does not report says which basis it is missing on.
//
// The boards take their payload as a prop rather than reading the store here,
// because a static render sees a zustand store's INITIAL state and never its
// current one; the store-reading default exports are the page the shell mounts.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { View } from '../src/features/views';
import type { SessionRow } from '../src/entities/session';
import type { TranscriptMessage } from '../src/entities/transcript';
import { SessionsBoard } from '../src/pages/sessions';
import { ProjectsBoard } from '../src/pages/projects';
import { groupByOf, groupHref, isTimeline, parseHours, selectionOf, windowOf } from '../src/pages/sessions/select';
import { edgeOf, fieldsOf } from '../src/pages/sessions/strip';
import { Conversation } from '../src/widgets/conversation';
import { FileView } from '../src/widgets/file-view';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

const HOUR = 3_600_000;
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

function view(over: Partial<View> = {}): View {
  return { id: 'v', name: 'v', layout: 'rack', filter: {}, sort: null, group: null, fields: ['name', 'peer'], ...over };
}

function message(over: Partial<TranscriptMessage> = {}): TranscriptMessage {
  return { index: 0, role: 'assistant', text: 'BODY-TEXT-NOT-IN-MARKUP', ...over };
}

const payload = (sessions: SessionRow[]) => ({ note: 'headless `claude -p` runs never register', sessions });

// ── what a view asks for ─────────────────────────────────────────────────────

describe('view selection', () => {
  const rows: SessionRow[] = [
    session({ session_id: 'a', head: 'claudex', repo: { root: '/dev/mythos' }, team: 'web-console' }),
    session({ session_id: 'b', head: 'claudex', cwd: '/dev/solo' }),
    session({ session_id: 'c', head: 'claude-grok', cwd: null }),
  ];

  test('reads the group out of the view, and the layout for the timeline', () => {
    expect(groupByOf(view({ group: 'head' }))).toBe('head');
    expect(groupByOf(view({ group: 'repo' }))).toBe('repo');
    expect(groupByOf(view({ group: 'team' }))).toBe('team');
    expect(groupByOf(view({ group: null }))).toBeNull();
    expect(isTimeline(view({ layout: 'timeline' }))).toBe(true);
    expect(isTimeline(view())).toBe(false);
  });

  test('groups by head, by project and by team', () => {
    const byHead = selectionOf(rows, view({ group: 'head' }), T0);
    expect(byHead.kind === 'groups' && byHead.groups.map((g) => [g.key, g.count])).toEqual([
      ['claudex', 2],
      ['claude-grok', 1],
    ]);

    const byRepo = selectionOf(rows, view({ group: 'repo' }), T0);
    expect(byRepo.kind === 'groups' && byRepo.groups.map((g) => g.key).sort()).toEqual([
      '/dev/mythos',
      '/dev/solo',
      'unattributed',
    ]);

    const byTeam = selectionOf(rows, view({ group: 'team' }), T0);
    expect(byTeam.kind === 'groups' && byTeam.groups.map((g) => [g.key, g.count])).toEqual([
      ['unattributed', 2],
      ['web-console', 1],
    ]);
  });

  test('a rack view with no group still draws a rack, grouped by head', () => {
    const selection = selectionOf(rows, view({ group: null }), T0);
    expect(selection.kind === 'groups' && selection.groups.length).toBe(2);
  });

  test('the timeline places rows on the clock and reports the undated ones', () => {
    const selection = selectionOf(
      [session({ started_at: T0 - 60_000 }), session({ started_at: null })],
      view({ layout: 'timeline', filter: { window: '2h', bucket: '1h' } }),
      T0,
    );
    expect(selection.kind).toBe('timeline');
    if (selection.kind !== 'timeline') return;
    expect(selection.window.hours).toBe(2);
    expect(selection.window.bucketMs).toBe(HOUR);
    // Two buckets, and the idle one is PRESENT: a bucket list with holes would
    // restate the day.
    expect(selection.timeline.buckets).toHaveLength(2);
    expect(selection.timeline.buckets[0].sessions).toHaveLength(0);
    expect(selection.timeline.buckets[1].sessions).toHaveLength(1);
    expect(selection.timeline.undated).toHaveLength(1);
  });

  test('reads the window from the view filter, and falls back rather than emptying the board', () => {
    expect(parseHours('24h')).toBe(24);
    expect(parseHours('0h')).toBeNull();
    expect(parseHours('day')).toBeNull();
    const fallback = windowOf(view({ filter: { window: 'nope', bucket: 'nope' } }), T0);
    expect(fallback.hours).toBe(24);
    expect(fallback.bucketMs).toBe(HOUR);
    expect(fallback.to).toBe(T0);
    expect(fallback.from).toBe(T0 - 24 * HOUR);
  });

  test('a group header opens the page that group belongs to', () => {
    expect(groupHref('head')).toBe('#/fleet');
    expect(groupHref('repo')).toBe('#/projects');
    expect(groupHref('team')).toBe('#/teams');
    expect(groupHref(null)).toBe('#/fleet');
  });
});

// ── the strip's own projection ───────────────────────────────────────────────

describe('session strip', () => {
  test('marks every value the client did not write with the absence glyph, never as a blank', () => {
    const bare = session({
      cwd: null,
      started_at: null,
      updated_at: null,
      session_id: null,
      name: null,
      pid: 42,
    });
    const fields = fieldsOf(bare, null, ['name', 'project', 'started', 'seen', 'peer']);
    expect(fields.map((f) => f.value)).toEqual(['pid 42', 'n/r', 'n/r', 'n/r', 'n/r']);
    // No basis word on an absent cell: the glyph is the whole statement (m1 design review B8).
    expect(fields.slice(1).every((f) => f.basis === undefined)).toBe(true);
    // The name a session falls back to is never empty either.
    expect(fields[0].basis).toBe('measured');
  });

  test('a measured value carries the measured basis and no suffix word', () => {
    const fields = fieldsOf(session({ repo: { root: '/dev/mythos' } }), 'gs-backend-claude', ['project', 'peer']);
    expect(fields.map((f) => [f.label, f.value, f.basis])).toEqual([
      ['project', 'mythos', 'measured'],
      ['peer', 'gs-backend-claude', 'measured'],
    ]);
  });

  test('gone is grey, never struck', () => {
    expect(edgeOf(session({ availability: 'live' }))).toBe('green');
    expect(edgeOf(session({ availability: 'stale' }))).toBe('amber');
    expect(edgeOf(session({ availability: 'gone' }))).toBe('grey');
  });
});

// ── the boards ───────────────────────────────────────────────────────────────

describe('sessions board', () => {
  test('prints the availability word on every strip, and cocks the stale one', () => {
    const out = render(
      h(SessionsBoard, {
        payload: payload([
          session({ session_id: 'live-one', availability: 'live' }),
          session({ session_id: 'stale-one', availability: 'stale' }),
          session({ session_id: 'gone-one', availability: 'gone' }),
        ]),
      }),
    );

    expect(out).toContain('>live<');
    expect(out).toContain('>stale<');
    expect(out).toContain('>gone<');
    expect(out).toContain('myx-strip-cocked'); // stale is the one that needs the operator
    expect(out).not.toContain('myx-strip-struck'); // a gone session is read, not disabled
    expect(out).toContain('head: claudex');
  });

  test('the peer is unknown, and prints the absence glyph, while the edges route is pending', () => {
    const out = render(h(SessionsBoard, { payload: payload([session({ session_id: 'a' })]) }));
    expect(out).toContain('>n/r<');
    expect(out).not.toContain('unavailable');
  });

  test('an empty registry names the route it read, and never a fixture', () => {
    const out = render(h(SessionsBoard, { payload: payload([]) }));
    expect(out).toContain('no sessions registered');
    expect(out).toContain('/api/sessions');
    expect(out).not.toContain('sample data');
  });

  test('the headless note is behind a reveal, and a sample board says so', () => {
    const out = render(h(SessionsBoard, { payload: payload([]), sample: true }));
    expect(out).toContain('myx-reveal-btn');
    expect(out).not.toContain('headless `claude -p` runs never register');
    expect(out).toContain('sample data');
  });
});

describe('projects board', () => {
  test('a pending list names the row that will serve it', () => {
    const out = render(h(ProjectsBoard, { payload: { pending: 'V4-131' } }));
    expect(out).toContain('row V4-131');
    expect(out).not.toContain('sample data');
  });

  test('an empty list names the route it read', () => {
    const out = render(h(ProjectsBoard, { payload: { projects: [] } }));
    expect(out).toContain('no repositories seen');
    expect(out).toContain('/api/projects');
  });

  test('a repo with no declared rates says so instead of costing zero', () => {
    const out = render(
      h(ProjectsBoard, {
        payload: {
          projects: [
            {
              id: '/dev/mythos',
              root: '/dev/mythos',
              live_sessions: 2,
              teams: 1,
              turns_today: 41,
              cost_today_usd: null,
              day_start: 0,
              last_activity: null,
            },
          ],
        },
      }),
    );
    expect(out).toContain('>n/r<');
    expect(out).not.toContain('no rates');
    expect(out).toContain('/dev/mythos');
  });
});

// ── the widgets ──────────────────────────────────────────────────────────────

describe('conversation', () => {
  test('the body is behind a reveal and is not in the markup by default', () => {
    const out = render(
      h(Conversation, {
        sessionId: 's1',
        slice: {
          sessionId: 's1',
          path: '/home/marcos/.claude/projects/x/s1.jsonl',
          messages: [message({ index: 3, ts: T0 })],
          cursor: { sessionId: 's1', next: 'tok', pages: 1, complete: false },
        },
      }),
    );
    expect(out).toContain('myx-reveal-btn');
    expect(out).not.toContain('BODY-TEXT-NOT-IN-MARKUP');
    expect(out).toContain('>assistant<');
    expect(out).toContain('/home/marcos/.claude/projects/x/s1.jsonl');
  });

  test('a pending transcript names the row that will serve it', () => {
    expect(render(h(Conversation, { sessionId: 's1', slice: { pending: 'V4-130' } }))).toContain('row V4-130');
  });

  test('a finished transcript offers no load more', () => {
    const out = render(
      h(Conversation, {
        sessionId: 's1',
        slice: {
          sessionId: 's1',
          path: '/p',
          messages: [message()],
          cursor: { sessionId: 's1', next: null, pages: 1, complete: true },
        },
      }),
    );
    expect(out).not.toContain('load more');
  });
});

describe('file view', () => {
  test('a pending files route names the row, and a memory-less project names its setting', () => {
    expect(render(h(FileView, { projectId: 'p', files: { pending: 'V4-131' } }))).toContain('row V4-131');

    const empty = render(
      h(FileView, {
        projectId: 'p',
        files: {
          id: 'p',
          files: [],
          looked_in: ['/home/marcos/.claude/projects/x/memory'],
          auto_memory_enabled: false,
        },
      }),
    );
    expect(empty).toContain('client memory is switched off');
    expect(empty).toContain('/home/marcos/.claude/projects/x/memory');
  });

  test('lists each file and keeps its contents behind the reveal', () => {
    const out = render(
      h(FileView, {
        projectId: 'p',
        files: {
          id: 'p',
          looked_in: ['/repo'],
          files: [{ kind: 'instructions', path: '/repo/CLAUDE.md', head: null, text: 'FILE-BODY-NOT-IN-MARKUP' }],
        },
      }),
    );
    expect(out).toContain('/repo/CLAUDE.md');
    expect(out).toContain('>repo<'); // a head-less file belongs to the repo, and says so
    expect(out).toContain('myx-reveal-btn');
    expect(out).not.toContain('FILE-BODY-NOT-IN-MARKUP');
  });
});
