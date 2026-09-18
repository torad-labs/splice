// TEAM BOARD, the hero (row M1-05). Three claims, each one a thing that could
// silently stop being true:
//
//   1. the board renders the comp's words — all of them, listed from the spec's
//      text regions, because the operator approved that comp with those words;
//   2. against a daemon that has no teams route yet, the page prints the honest
//      empty naming the work item rather than an empty board;
//   3. the fixture cannot reach a production bundle: its bytes are behind the
//      dev guard, and the built file is grepped for them in the row's note.
//
// A .ts file holds no JSX (CONTRACTS.md section 4), so the elements are built
// with createElement and read back through renderToStaticMarkup.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

import { PENDING_TEAMS } from '../src/entities/team';
import { MgmtError, pendingOf } from '../src/shared/api';
import {
  TeamBoard, TeamBoardByRole, TeamTimeline, costPerRole, groupByRole, roleRows, timeRule,
  timelineRows, turnsPerMember,
} from '../src/widgets/team-board';
import { TeamChat, chatOrder } from '../src/widgets/team-chat';
import { ActivityFeed, feedEmpty, feedOrder } from '../src/widgets/activity-feed';
import { TeamCompose, bindSession, blankDraft, draftOf, setArchived, unbindSession, validateDraft } from '../src/features/team-compose';
import { heroBoard, viewsBoard, viewsData } from '../src/pages/teams/fixtures/hero';
import { teamsBodyFor } from '../src/pages/teams';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

/** Static markup escapes quotes and ampersands; the words are the words. */
function unescapeHtml(html: string): string {
  return html
    .replace(/&quot;/g, '"')
    .replace(/&#x27;/g, "'")
    .replace(/&gt;/g, '>')
    .replace(/&lt;/g, '<')
    .replace(/&amp;/g, '&');
}

const boardHtml = unescapeHtml(renderToStaticMarkup(createElement(TeamBoard, { board: heroBoard })));

// Every word the comp prints, gathered from the spec's text and control
// regions: the header strip, the two session strips' three lines each, the bay
// labels, the chat strips, the activity strips and the footer.
const COMP_WORDS: string[] = [
  // team header strip
  'team', 'goal', 'repo', 'slots', 'lead driving',
  'grailseeker-backend', 'promoter bookkeeping lives on fly raw',
  '~/Documents/dev/infra/grailseeker-backend', '3 slots, 3 bound', 'gs-backend-claude',
  // session strip, line one
  'name', 'role', 'model', 'account', 'window', 'last turn', 'state',
  'lead', 'fable', 'acct-a', '5h 74%', '14:01', 'driving',
  // session strip, line two
  'head', 'session id', 'created', 'uptime', 'turns', 'tokens in', 'tokens out', 'cost est',
  'claude', 's-1a7c9e2d', '09:12:33', '4h 49m', '27', '182,341', '71,552', '$0.412',
  // session strip, line three
  'context left', 'scratchpad', 'workspace', 'branch', 'base', 'diff', 'checks',
  '35%', '12.8 k', '~/.../grailseeker-backend', 'feature/gs-41', 'main', '+412 -37', 'pass',
  // the builder's three lines
  'gs-backend-builder', 'builder', 'deepseek-flash', 'n/r', '13:58', 'building GS-41 done',
  'claude-deepseek', 's-5f3b8a11', '09:18:02', '4h 44m', '18', '93,274', '38,611', '$0.183',
  '8.1 k', '+289 -37',
  // bay labels
  'head: claude', 'head: claude-deepseek',
  'team chat (newest at bottom)', 'activity, sampled every 30 s',
  // the chat strips
  'time', 'from', '→', 'to', 'packet', 'message',
  '13:41', 'packet GS-41: promoter bookkeeping lives on fly raw. build, run parity, report',
  'GS-41 done, see ledger', 'gs-backend-builder2', 'GS-42',
  'packet GS-42: dearm the machine-update schedule',
  // the activity strips
  'member', 'activity', 'detail',
  '14:01:30', 'editing PromoterLedger.kt', 'src/ledger/PromoterLedger.kt:142',
  'running checks/gate.sh', 'checks/gate.sh --all',
  'searching for fly_raw', 'rg -n "fly_raw"',
  '14:02:00', 'reading MANIFEST.toml', 'MANIFEST.toml',
  'messaging a peer session', 'to gs-backend-builder2',
  // the footer
  'team id:', 'team_7f2c1b4a', 'created:', '2025-05-22 09:12:33', 'updated:', '2025-05-22 14:02:05',
];

describe('the board renders the comp', () => {
  test('the fixture is the comp, word for word', () => {
    const missing = COMP_WORDS.filter((word) => !boardHtml.includes(word));
    expect(missing, 'words the comp prints and the board does not').toEqual([]);
  });

  test('every comp word is a real string in the output, not markup', () => {
    // The board's own element, wherever it sits: M3-03 wrapped it in the phone's scroll frame
    // (`myx-board-frame`, `display: contents` on a desktop), so the render now opens on that div.
    // What this arm is for is that the words above were found in RENDERED markup rather than in an
    // escaped string, and the board's section is what says so.
    expect(boardHtml.startsWith('<')).toBe(true);
    expect(boardHtml).toContain('<section class="myx-board"');
    expect(boardHtml.length).toBeGreaterThan(2000);
  });

  test('the board racks one bay per head the members run on', () => {
    expect(boardHtml).toContain('head: claude');
    expect(boardHtml).toContain('head: claude-deepseek');
  });

  test('a figure no provider reports prints n/r rather than a zero', () => {
    const builder = heroBoard.members[1];
    expect(builder.window).toBeNull();
    expect(builder.contextLeftPct).toBeNull();
    expect(boardHtml).toContain('n/r');
    // Read the cells, not the markup: the board's rows carry their own pitch in
    // a style attribute, and a row at the top of its rack is legitimately 0%.
    const values = [...boardHtml.matchAll(/myx-sfield-text">([^<]*)</g)].map((match) => match[1]);
    expect(values).not.toContain('0%');
  });
});

describe('against a daemon that has no teams route', () => {
  test('the page prints the honest empty naming V4-131', () => {
    const pending = { pending: 'V4-131' };
    const html = unescapeHtml(renderToStaticMarkup(teamsBodyFor({ fixture: null, view: 'by-head', teams: pending, team: pending })));
    expect(html).toContain('no teams route');
    expect(html).toContain('V4-131 pending');
  });

  test('before anything has answered, the page claims neither absence nor failure', () => {
    const html = unescapeHtml(renderToStaticMarkup(teamsBodyFor({ fixture: null, view: 'by-head', teams: null, team: null })));
    expect(html).toContain('reading teams');
    expect(html).not.toContain('V4-131 pending');
    expect(html).not.toContain('unreadable');
  });

  test('a 404 is what makes it the honest empty, through the shared mapping', () => {
    const pending = pendingOf(new MgmtError(404, 'unknown route'), PENDING_TEAMS);
    expect(pending).toEqual({ pending: 'V4-131' });
    const html = unescapeHtml(
      renderToStaticMarkup(teamsBodyFor({ fixture: null, view: 'by-head', teams: pending, team: pending })),
    );
    expect(html).toContain('V4-131 pending');
  });

  test('a daemon that answers with no teams is not dressed as a missing route', () => {
    const empty = { teams: [] };
    const html = unescapeHtml(
      renderToStaticMarkup(teamsBodyFor({ fixture: null, view: 'by-head', teams: empty, team: null })),
    );
    expect(html).toContain('no teams yet');
    expect(html).not.toContain('V4-131 pending');
  });

  test('the other two views read the live team, and their pending panels name V4-131', () => {
    for (const view of ['by-role', 'timeline']) {
      const html = unescapeHtml(renderToStaticMarkup(teamsBodyFor({ fixture: heroBoard, view, teams: null, team: null })));
      expect(html, view).not.toContain('view not built');
      expect(html, view).toContain('grailseeker-backend');
      // No fixture view data: every panel that reads a route still to come says which row serves it.
      expect(html, view).toContain('V4-131 pending');
    }
  });
});

describe('the fixture stays out of a production bundle', () => {
  const source = read('webui/src/pages/teams/index.tsx');

  test('the fixture is never reached by a static import, and its specifier is not a literal', () => {
    // A static import makes the fixture reachable whether or not the guard's branch runs, so the
    // bundler keeps its bytes. Measured: the comp's words were in dist/index.html until this import
    // became a dynamic one. And the specifier must be COMPOSED AT RUNTIME (M1-20): a statically
    // analyzable `import('./fixtures/hero')` stays a dependency edge through the single-file build
    // even when the branch around it is dead, which shipped this fixture's bytes again - the wall
    // named 15 of them.
    expect(source).not.toMatch(/^import .*from '\.\/fixtures\//m);
    expect(source).toContain('import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`)');
  });

  test('every use of the fixture sits behind the dev guard', () => {
    const guard = source.indexOf('import.meta.env.DEV');
    expect(guard, 'the dev guard is gone from the page').toBeGreaterThan(-1);
    const uses = [...source.matchAll(/heroBoard/g)].map((match) => match.index ?? -1);
    expect(uses.length, 'the fixture must be referenced at its load').toBeGreaterThan(0);
    for (const at of uses) expect(at).toBeGreaterThan(guard);
  });

  test('the guard is a static condition the bundler can eliminate', () => {
    // The guard moved into `wantsFixture` (M1-20) so that the load and the capture marker are one
    // decision, and it is still a STATIC conjunction: `import.meta.env.DEV` is replaced by false in
    // a production build, so the branch — and the fixture with it — is dropped. A guard read from a
    // variable would keep the branch reachable and the bytes with it, which is the measured leak
    // this whole group exists for.
    expect(source).toContain("return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;");
    // The branch is braced because it CLEARS the fixture state before returning: an early return
    // that left the previous value in place kept a stale capture marker across a hash change
    // (measured in a browser, M1-20).
    expect(source).toContain('if (!wantsFixture(search)) {');
    expect(source).toContain('setSample(null);');
  });
});

// ---- THE OTHER TWO VIEWS, THE PANELS AND THE COMPOSER (row M2-08) -----------------------------
//
// Six claims, one per rule the row names. Each one is a thing that could silently stop being true
// and that no typecheck would catch: a grouping, a column count, a validation, a flag, an order,
// and the difference between two empties that are not the same answer.
const roleHtml = unescapeHtml(renderToStaticMarkup(
  createElement(TeamBoardByRole, { board: viewsBoard, data: viewsData }),
));
const timelineHtml = unescapeHtml(renderToStaticMarkup(
  createElement(TeamTimeline, { board: viewsBoard, data: viewsData }),
));

describe('the board by role', () => {
  test('a bay exists for every role the team DECLARES, bound or not', () => {
    const bays = groupByRole(viewsBoard);
    expect(bays.map((bay) => bay.role)).toEqual(['lead', 'builder', 'reviewer']);
    expect(bays[0].members.map((m) => m.name)).toEqual(['gs-backend-claude']);
    expect(bays[1].members.map((m) => m.name)).toEqual(['gs-backend-builder', 'gs-backend-builder2']);
    expect(bays[2].members).toEqual([]);
  });

  test('the empty reviewer bay names the head an operator would launch', () => {
    const reviewer = groupByRole(viewsBoard)[2];
    expect(reviewer.open?.head).toBe('claudex');
    expect(roleHtml).toContain('no session bound, launch one: claudex');
  });

  test('a hand-off crosses from the sender bay to the recipient bay', () => {
    const crossing = roleRows(viewsBoard, groupByRole(viewsBoard))
      .filter((event): event is Extract<typeof event, { kind: 'message' }> => event.kind === 'message');
    expect(crossing).toHaveLength(3);
    // lead -> builder, builder -> lead, lead -> builder2: all three span bay 0 to bay 1.
    for (const event of crossing) expect([event.from, event.to]).toEqual([0, 1]);
    // Two sessions created in different bays share a row; every hand-off takes one of its own.
    const rows = roleRows(viewsBoard, groupByRole(viewsBoard)).map((event) => event.row);
    expect(rows[0]).toBe(rows[1]);
    expect(new Set(rows.slice(2)).size).toBe(rows.length - 2);
  });

  test('the rule reads from the quarter hour before the first hand-off to now', () => {
    expect(timeRule(viewsBoard.messages, '14:02').map((mark) => mark.label))
      .toEqual(['13:30', '13:45', '14:00', 'now']);
  });

  test('a window no provider reports is named, never zeroed', () => {
    expect(roleHtml).toContain('not reported by provider');
  });
});

describe('the timeline', () => {
  test('one column per member, and the member says which head it runs', () => {
    for (const member of viewsBoard.members) {
      expect(timelineHtml, member.name).toContain(member.name);
    }
    expect(timelineHtml).toContain('claude-deepseek (deepseek-flash) · builder');
    // Every bucket row carries one cell per member, whatever happened in it.
    const rows = timelineRows(viewsBoard, viewsData);
    for (const row of rows) {
      if (row.kind === 'bucket') expect(row.cells).toHaveLength(viewsBoard.members.length);
    }
  });

  test('a hand-off spans the columns between its sender and its recipient', () => {
    const rows = timelineRows(viewsBoard, viewsData)
      .filter((row): row is Extract<typeof row, { kind: 'message' }> => row.kind === 'message');
    expect(rows.map((row) => [row.from, row.to])).toEqual([[0, 1], [0, 1], [0, 2]]);
    expect(timelineHtml).toContain('lead → builder 2');
  });

  test('the turns still running are racked on the now row, not on a clock time', () => {
    const rows = timelineRows(viewsBoard, viewsData);
    const last = rows[rows.length - 1];
    expect(last.kind).toBe('bucket');
    if (last.kind === 'bucket') {
      expect(last.label).toBe('now');
      expect(last.cells.flat().every((cell) => cell.kind === 'turn' && cell.turn.live)).toBe(true);
    }
  });

  test('cost per role joins on the session prefix, counts what it cannot place, and names the oldest turn', () => {
    const table = costPerRole(viewsBoard, viewsData.economics);
    expect(table.rows.map((row) => [row.role, row.input, row.output, row.turns])).toEqual([
      ['lead', 132116, 56656, 6],
      ['builder', 194283, 78231, 13],
      ['reviewer', 0, 0, 0],
    ]);
    expect(table.total.total).toBe(461286);
    expect(table.oldest).toEqual({ id: 't-1801', at: '09:14' });

    // A session the join cannot place is a ROW, not a silence: the total stays the route's total.
    const stray = [...viewsData.economics, { session: 'zz-99999999', input: 10, output: 5, turns: 1, oldestTurnId: 't-1900', oldestTurnAt: '13:59' }];
    const withStray = costPerRole(viewsBoard, stray);
    expect(withStray.unattributed.turns).toBe(1);
    expect(withStray.total.total).toBe(461286 + 15);
    expect(timelineHtml).toContain('joined on the first 8 characters of the session id');
  });

  test('turns per member reads the same join as the cost table', () => {
    expect(turnsPerMember(viewsBoard, viewsData.economics)).toEqual([
      { member: 'gs-backend-claude', turns: 6 },
      { member: 'gs-backend-builder', turns: 8 },
      { member: 'gs-backend-builder2', turns: 5 },
    ]);
  });
});

describe('the chat panel', () => {
  test('the messages read down in time order, whatever order the route answered in', () => {
    const shuffled = [viewsBoard.messages[2], viewsBoard.messages[0], viewsBoard.messages[1]];
    expect(chatOrder(shuffled).map((message) => message.time)).toEqual(['13:41', '13:58', '14:01']);
  });

  test('the text is behind a reveal, because the daemon reads it on demand', () => {
    const html = unescapeHtml(renderToStaticMarkup(createElement(TeamChat, { state: { messages: viewsBoard.messages } })));
    expect(html).toContain('show message');
    expect(html).not.toContain('GS-41 done, see ledger');
  });

  test('a route that does not exist yet is not an empty chat', () => {
    const html = unescapeHtml(renderToStaticMarkup(createElement(TeamChat, { state: { pending: PENDING_TEAMS } })));
    expect(html).toContain('no chat route');
    expect(html).toContain('V4-131 pending');
  });
});

describe('the activity feed', () => {
  test('nothing sampled and a client that stopped matching are different answers', () => {
    expect(feedEmpty({ activity: [], clientMatching: true })?.text).toBe('nothing sampled yet');
    expect(feedEmpty({ activity: [], clientMatching: false })?.text).toBe('client no longer matching');
    expect(feedEmpty({ pending: PENDING_TEAMS })?.text).toBe('no activity route');
    expect(feedEmpty(null)?.text).toBe('reading activity');
    expect(feedEmpty({ activity: viewsBoard.activity, clientMatching: true })).toBeNull();
  });

  test('the feed is labelled a sample and reads newest first', () => {
    const html = unescapeHtml(renderToStaticMarkup(
      createElement(ActivityFeed, { state: { activity: viewsBoard.activity, clientMatching: true } }),
    ));
    expect(html).toContain('30 s sample');
    const order = feedOrder(viewsBoard.activity).map((entry) => entry.time);
    expect(order[0]).toBe('14:00');
    expect(order[order.length - 1]).toBe('13:30');
  });
});

describe('the composer', () => {
  test('it says what stops the draft being saved', () => {
    expect(validateDraft(blankDraft())).toEqual(['the team needs a name', 'the team needs a repo', 'slot 1 needs a role', 'slot 1 needs a head']);
    const draft = draftOf(viewsBoard.team);
    expect(validateDraft(draft)).toEqual([]);
    expect(validateDraft({ ...draft, slots: draft.slots.map((slot) => ({ ...slot, lead: true })) }))
      .toContain('one slot must lead, 4 do');
    expect(validateDraft(bindSession(draft, 1, 'gs-backend-claude')))
      .toContain('gs-backend-claude is bound to two slots');
  });

  test('archiving sets a flag and deletes nothing', () => {
    const draft = draftOf(viewsBoard.team);
    const archived = setArchived(draft, true);
    expect(archived.archived).toBe(true);
    expect(archived.slots).toEqual(draft.slots);
    expect(archived.slots.filter((slot) => slot.session !== null)).toHaveLength(3);
    // And it round-trips: restoring gives back the team that was archived.
    expect(setArchived(archived, false)).toEqual(draft);
  });

  test('unbinding frees the seat and keeps the slot', () => {
    const draft = draftOf(viewsBoard.team);
    const free = unbindSession(draft, 0);
    expect(free.slots).toHaveLength(draft.slots.length);
    expect(free.slots[0].session).toBeNull();
    expect(free.slots[0].role).toBe('lead');
  });

  test('the form cannot promise a save the daemon has no route for', () => {
    const html = unescapeHtml(renderToStaticMarkup(createElement(TeamCompose, {})));
    expect(html).toContain('the console cannot save a team yet');
    expect(html).toContain('V4-131 serves PUT /api/teams');
  });
});
