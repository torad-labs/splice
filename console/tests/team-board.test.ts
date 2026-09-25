// THE TEAMS PAGE ON THE KIT (the console redesign, 2026-09-25). Each claim is a thing that could
// silently stop being true and that no typecheck would catch: a seat dropped from its run, a figure
// printed where no route reports one, a turn drawn off its clock, a cost summed twice, two empties
// that are not the same answer, and the sample team reaching a production bundle.
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
import type { TeamMemberRow, TeamPayload } from '../src/entities/team';
import { MgmtError, pendingOf } from '../src/shared/api';
import {
  CostPerRole, SeatDetail, TeamMembers, TeamStats, TeamTimeline, costTable, dayAxis, lanesOf, rolesOf, seatGroups, seatsOf,
  slotName, stateOf,
} from '../src/widgets/team-board';
import { TeamChat, chatOrder } from '../src/widgets/team-chat';
import { ActivityFeed, FEED_ROWS, feedEmpty, feedOrder } from '../src/widgets/activity-feed';
import { TeamCompose, bindSession, blankDraft, draftOf, optionsFor, setArchived, unbindSession, validateDraft } from '../src/features/team-compose';
import { sampleBoard, sampleData } from '../src/pages/teams/fixtures/hero';
import { TeamList, modeOf, teamsBodyFor } from '../src/pages/teams';

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
const render = (element: Parameters<typeof renderToStaticMarkup>[0]): string => unescapeHtml(renderToStaticMarkup(element));

const BY_HEAD = { layout: 'table', group: 'head' };
const BY_ROLE = { layout: 'table', group: 'role' };
const TIMELINE = { layout: 'timeline', group: null };

const pageHtml = render(teamsBodyFor({
  view: BY_HEAD,
  teams: { teams: [sampleBoard.team] },
  board: sampleBoard,
  data: sampleData,
  chat: { messages: sampleBoard.messages },
  feed: { activity: sampleBoard.activity, clientMatching: true },
}));

/** The cells of the members table's row whose open button names `name`. */
const rowOf = (html: string, name: string): string => {
  const at = html.indexOf(`aria-label="Member detail ${name}"`);
  const start = html.lastIndexOf('<tr', at);
  return html.slice(start, html.indexOf('</tr>', at));
};

describe('the opened team', () => {
  test('its name, its goal and repo, and its figures head it', () => {
    expect(pageHtml).toContain('storefront-api');
    expect(pageHtml).toContain('promoter bookkeeping lives on fly raw');
    expect(pageHtml).toContain('~/Documents/dev/infra/storefront-api');
    const stats = render(createElement(TeamStats, { board: sampleBoard, data: sampleData }));
    // Three of four slots are bound, as pips and as the figure.
    expect(stats).toContain('Slots bound: 3 of 4');
    expect(stats).toMatch(/myx-stat-value">3<span class="myx-stat-unit">of 4</);
    // The lifetime figures are the role tallies summed: 19 turns, 461k tokens, $0.736.
    expect(stats).toContain('>19<');
    expect(stats).toContain('461k');
    expect(stats).toContain('$0.736');
    expect(stats).toContain('2 untagged');
    expect(stats).toContain('>3<span class="myx-stat-unit">today');
  });

  test('in flight is the running count, and a dash, never a zero, before the heads answer', () => {
    const inFlight = (html: string) => /In flight[\s\S]*?myx-stat-value">([^<]*)</.exec(html)?.[1];
    expect(inFlight(render(createElement(TeamStats, { board: sampleBoard, data: sampleData })))).toBe('3');
    expect(inFlight(render(createElement(TeamStats, { board: sampleBoard, data: { ...sampleData, inFlight: null } })))).toBe('–');
  });

  test('every seat is a row, an open one included, grouped under the head it runs on', () => {
    for (const member of sampleBoard.members) expect(pageHtml, member.name).toContain(member.name);
    expect(pageHtml).toContain('Open seat');
    const groups = seatGroups(sampleBoard, 'head');
    expect(groups.map((group) => [group.key, group.seats.length])).toEqual([['claudex', 1], ['claude-grok', 2], ['bonsai-2-27b', 1]]);
  });

  test("a member's tokens are one bar on the table's scale, with the sum after it", () => {
    const lead = rowOf(pageHtml, 'api-lead');
    expect(lead).toContain('Tokens: Tokens in 132k, Tokens out 57k');
    expect(lead).toContain('189k');
    expect(lead).toContain('$0.412');
    expect(lead).toContain('Pass');
  });

  test('a figure no route reports prints its absence, and a field no member has leaves no column', () => {
    // The lead reports a window and the builder does not: the column stands and the builder's cell is –.
    expect(pageHtml).toContain('>Window<');
    expect(rowOf(pageHtml, 'api-builder')).toMatch(/myx-dt-mono">–</);
    // No member reporting one: no Window column at all, rather than a column of dashes.
    const none: TeamPayload = { ...sampleBoard, members: sampleBoard.members.map((member) => ({ ...member, window: null })) };
    expect(render(createElement(TeamMembers, { board: none, by: 'head' }))).not.toContain('>Window<');
  });

  test('by role, the roles run in the order the team declares them, the lead first', () => {
    expect(seatGroups(sampleBoard, 'role').map((group) => [group.key, group.seats.map((seat) => seat.member?.name ?? null)])).toEqual([
      ['lead', ['api-lead']],
      ['builder', ['api-builder', 'api-builder-2']],
      ['reviewer', [null]],
    ]);
    const html = render(teamsBodyFor({ view: BY_ROLE, teams: null, board: sampleBoard, data: sampleData }));
    // By role, the head is a column, so the open reviewer seat still says what it would run on.
    expect(html).toContain('>Head<');
    expect(rowOf(html, 'reviewer')).toContain('claude-grok');
  });

  test("an open seat's detail says nothing is bound and shows the slot", () => {
    const open = seatsOf(sampleBoard).find((seat) => seat.member === null);
    if (open === undefined) throw new Error('the sample carries an open seat');
    const html = render(createElement(SeatDetail, { board: sampleBoard, seat: open }));
    expect(html).toContain('No session bound');
    expect(html).toContain('claude-grok');
    expect(html).toContain('No instructions');
    expect(stateOf(open)).toEqual({ tone: 'neutral', word: 'Open' });
  });

  test("a bound seat's detail keeps its instructions behind a reveal and prints only what is reported", () => {
    const lead = seatsOf(sampleBoard)[0];
    const html = render(createElement(SeatDetail, { board: sampleBoard, seat: lead }));
    expect(html).toContain('Show instructions');
    expect(html).not.toContain('Own the packet queue');
    expect(html).toContain('s-1a7c9e2d');
    expect(html).toContain('feature/gs-41');
    // The newest hand-off the lead received.
    expect(html).toContain('13:58');
    const bare: TeamMemberRow = { ...sampleBoard.members[0], branch: null, base: null, diff: null, window: null, contextLeftPct: null, scratchpadKb: null };
    const plain = render(createElement(SeatDetail, { board: { ...sampleBoard, members: [bare] }, seat: { ...lead, member: bare } }));
    for (const label of ['Branch', 'Base', 'Diff', 'Window', 'Context left', 'Scratchpad']) expect(plain, label).not.toContain(`>${label}<`);
  });
});

describe('the lead is the slot flagged lead, whatever its role is called', () => {
  const [lead, builder] = sampleBoard.team.slots;
  const team = { ...sampleBoard.team, slots: [{ ...builder, role: 'lead', lead: false }, { ...lead, role: 'architect' }] };
  const board: TeamPayload = { ...sampleBoard, team, members: [] };

  test('the declared roles put the flagged slot first', () => {
    expect(rolesOf(team.slots)).toEqual(['architect', 'lead']);
  });

  test('the lead badge sits on the flagged slot, not on the role named lead', () => {
    const html = render(createElement(TeamMembers, { board, by: 'role' }));
    expect(rowOf(html, 'architect')).toContain('aria-label="Lead"');
    expect(rowOf(html, 'lead')).not.toContain('aria-label="Lead"');
  });
});

describe('the views', () => {
  test('a view draws by its layout and group, so a renamed or copied view keeps drawing', () => {
    expect(modeOf(BY_HEAD)).toBe('head');
    expect(modeOf(BY_ROLE)).toBe('role');
    expect(modeOf(TIMELINE)).toBe('timeline');
    expect(modeOf({ layout: 'board', group: 'role' })).toBe('role');
  });

  test('the team list names every team, its bound slots and its state', () => {
    const second = { ...sampleBoard.team, id: 'team-2', name: 'checkout', archived: true };
    const html = render(createElement(TeamList, { teams: [sampleBoard.team, second], opened: 'team-2', onOpen: () => undefined }));
    expect(html).toContain('storefront-api');
    expect(html).toContain('checkout');
    expect(html).toContain('3 of 4');
    expect(html).toContain('Archived');
    expect(html).toContain('Active');
  });
});

describe('what the page says when no team answered', () => {
  test('a daemon older than the teams route says so in words, never a row id', () => {
    const html = render(teamsBodyFor({ view: BY_HEAD, teams: { pending: 'V4-131' }, board: null }));
    expect(html).toContain('Teams unavailable');
    expect(html).toContain('does not serve teams');
    expect(html).not.toContain('V4-131');
  });

  test('before anything has answered, the page claims neither absence nor failure', () => {
    const html = render(teamsBodyFor({ view: BY_HEAD, teams: null, board: null }));
    expect(html).toContain('Reading teams');
    expect(html).not.toContain('unavailable');
    expect(html).not.toContain('unreadable');
  });

  test("a list read that failed says so, in the daemon's words", () => {
    const html = render(teamsBodyFor({ view: BY_HEAD, teams: null, board: null, error: 'HTTP 500' }));
    expect(html).toContain('Teams unreadable');
    expect(html).toContain('HTTP 500');
  });

  test('a 404 is what makes it the honest empty, through the shared mapping', () => {
    const pending = pendingOf(new MgmtError(404, 'unknown route'), PENDING_TEAMS);
    expect(pending).toEqual({ pending: 'V4-131' });
    expect(render(teamsBodyFor({ view: BY_HEAD, teams: pending, board: null }))).toContain('Teams unavailable');
  });

  test('a daemon that answers with no teams is not dressed as a missing route, and offers the first', () => {
    const html = render(teamsBodyFor({ view: BY_HEAD, teams: { teams: [] }, board: null, onNew: () => undefined }));
    expect(html).toContain('No teams yet');
    expect(html).toMatch(/<button[^>]*>(?:(?!<\/button>)[\s\S])*New team/);
    expect(html).not.toContain('Teams unavailable');
  });

  test('every view draws the opened team, and a panel still being read says so', () => {
    for (const view of [BY_HEAD, BY_ROLE, TIMELINE]) {
      const html = render(teamsBodyFor({ view, teams: null, board: sampleBoard }));
      expect(html, view.group ?? view.layout).toContain('storefront-api');
      expect(html).toContain('Reading API costs');
      expect(html).toContain('Reading the chat');
      expect(html).toContain('Reading activity');
    }
    expect(render(teamsBodyFor({ view: TIMELINE, teams: null, board: sampleBoard }))).toContain('Reading turns');
  });
});

describe('the sample team stays out of a production bundle', () => {
  const source = read('console/src/pages/teams/index.tsx');

  test('the fixture is never reached by a static import, and its specifier is not a literal', () => {
    // A static import makes the fixture reachable whether or not the guard's branch runs, so the
    // bundler keeps its bytes. And the specifier must be COMPOSED AT RUNTIME (M1-20): a statically
    // analyzable `import('./fixtures/hero')` stays a dependency edge through the single-file build
    // even when the branch around it is dead.
    expect(source).not.toMatch(/^import .*from '\.\/fixtures\//m);
    expect(source).toContain('import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`)');
  });

  test('every use of the fixture sits behind the dev guard', () => {
    const guard = source.indexOf('import.meta.env.DEV');
    expect(guard, 'the dev guard is gone from the page').toBeGreaterThan(-1);
    const uses = [...source.matchAll(/sampleBoard/g)].map((match) => match.index ?? -1);
    expect(uses.length, 'the fixture must be referenced at its load').toBeGreaterThan(0);
    for (const at of uses) expect(at).toBeGreaterThan(guard);
  });

  test('the guard is a static condition the bundler can eliminate', () => {
    expect(source).toContain("return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;");
    // The branch CLEARS the fixture state before returning: an early return that left the previous
    // value in place kept a stale capture marker across a hash change (measured in a browser, M1-20).
    expect(source).toContain('if (!wantsFixture(search)) {');
    expect(source).toContain('setSample(null);');
  });
});

describe("the day's timeline", () => {
  const html = render(createElement(TeamTimeline, { board: sampleBoard, data: sampleData }));

  test('one lane per member, each holding only its own turns, oldest first', () => {
    const lanes = lanesOf(sampleBoard, sampleData.turns);
    expect(lanes.map((lane) => [lane.member.name, lane.turns.length])).toEqual([
      ['api-lead', 6],
      ['api-builder', 6],
      ['api-builder-2', 1],
    ]);
    for (const lane of lanes) {
      expect(lane.turns.every((turn) => turn.member === lane.member.name)).toBe(true);
      expect(lane.turns.map((turn) => turn.start)).toEqual([...lane.turns.map((turn) => turn.start)].sort((a, b) => a - b));
    }
    expect(html).toContain('api-lead: 6 turns, 1 running');
  });

  test('the clock reaches from before the first turn to now, on at most eight ticks', () => {
    const axis = dayAxis(sampleData.turns.map((turn) => turn.start), sampleData.now);
    expect(axis.to).toBe(sampleData.now);
    expect(axis.from).toBeLessThanOrEqual(Math.min(...sampleData.turns.map((turn) => turn.start)));
    expect(axis.ticks.length).toBeLessThanOrEqual(8);
    // The first tick stands on a whole local hour, within a step of the first turn.
    const first = new Date(axis.ticks[0].at);
    expect([first.getMinutes(), first.getSeconds()]).toEqual([0, 0]);
    expect(axis.ticks[0].label).toBe(`${String(first.getHours()).padStart(2, '0')}:00`);
    // An idle team still gets the last hour, not a zero-width clock.
    const idle = dayAxis([], sampleData.now);
    expect(idle.to - idle.from).toBeGreaterThanOrEqual(3_600_000);
  });

  test('a running turn is drawn in the running mark, and a landed one in the grey', () => {
    expect((html.match(/myx-tt-turn myx-mark-ok/g) ?? []).length).toBe(3);
    expect((html.match(/myx-tt-turn myx-mark-series-1/g) ?? []).length).toBe(10);
  });

  test('a hand-off is a tick on its own lane, named by the seats it crossed', () => {
    expect((html.match(/myx-tt-msg/g) ?? []).length).toBe(3);
    expect(html).toContain('14:01 lead → builder 2');
    expect(slotName(sampleBoard, 'a-stranger')).toBe('a-stranger');
  });
});

describe('the cost per role', () => {
  const economics = sampleData.economics;
  if ('error' in economics) throw new Error('the sample carries the economics payload');

  test("it prints the daemon's tallies, and what it could not place", () => {
    const table = costTable(economics);
    expect(table.rows.map((row) => [row.role, row.input, row.output, row.cost, row.turns])).toEqual([
      ['lead', 132116, 56656, 0.412, 6],
      ['builder', 194283, 78231, 0.324, 13],
    ]);
    expect(table.total.input + table.total.output).toBe(461286);
    expect(table.total.cost).toBeCloseTo(0.736, 6);
    const html = render(createElement(CostPerRole, { data: sampleData }));
    expect(html).toContain('2 untagged');
    const oldest = new Date(economics.oldest_turn_epoch_millis ?? 0);
    const two = (value: number) => String(value).padStart(2, '0');
    expect(html).toContain(`since ${oldest.getFullYear()}-${two(oldest.getMonth() + 1)}-${two(oldest.getDate())} ${two(oldest.getHours())}:${two(oldest.getMinutes())}`);
  });

  test('cache reads and writes count as tokens in, and one unpriced role unprices the total', () => {
    const cached = {
      ...economics,
      roles: [{ ...economics.roles[0], tokens: { input: 10, cache_read: 1000, cache_write: 100, output: 5 } }, { ...economics.roles[1], cost_usd: null }],
    };
    const table = costTable(cached);
    expect(table.rows[0].input).toBe(1110);
    expect(table.total.cost).toBeNull();
    const stats = render(createElement(TeamStats, { board: sampleBoard, data: { ...sampleData, economics: cached } }));
    expect(stats).toContain('Unpriced');
  });

  test('an economics read that failed prints the reason, not an empty table', () => {
    const html = render(createElement(CostPerRole, { data: { ...sampleData, economics: { error: 'no such team: t' } } }));
    expect(html).toContain('Estimates unreadable');
    expect(html).toContain('no such team: t');
  });
});

describe('the chat', () => {
  test('the messages read down in time order, whatever order the route answered in', () => {
    const shuffled = [sampleBoard.messages[2], sampleBoard.messages[0], sampleBoard.messages[1]];
    expect(chatOrder(shuffled).map((message) => message.time)).toEqual(['13:41', '13:58', '14:01']);
  });

  test('the text is behind a reveal, because the daemon reads it on demand', () => {
    const html = render(createElement(TeamChat, { state: { messages: sampleBoard.messages } }));
    expect(html).toContain('Show message');
    expect(html).not.toContain('GS-41 done, see ledger');
    expect(html).toContain('aria-label="api-lead to api-builder"');
  });

  test('a route this daemon does not serve is not an empty chat, and says so without a row id', () => {
    const html = render(createElement(TeamChat, { state: { pending: PENDING_TEAMS } }));
    expect(html).toContain('Chat unavailable');
    expect(html).not.toContain('V4-131');
    expect(html).not.toContain('/api/');
  });
});

describe('the activity feed', () => {
  test('nothing sampled and a client that stopped matching are different answers', () => {
    expect(feedEmpty({ activity: [], clientMatching: true })?.text).toBe('Nothing sampled today');
    expect(feedEmpty({ activity: [], clientMatching: false })?.text).toBe('Client not matching');
    expect(feedEmpty({ pending: PENDING_TEAMS })?.text).toBe('Activity unavailable');
    expect(feedEmpty(null)?.text).toBe('Reading activity');
    expect(feedEmpty({ activity: sampleBoard.activity, clientMatching: true })).toBeNull();
  });

  test('the feed says it is a sample and reads newest first', () => {
    const html = render(createElement(ActivityFeed, { state: { activity: sampleBoard.activity, clientMatching: true } }));
    expect(html).toContain('sampled about every 30 seconds');
    const order = feedOrder(sampleBoard.activity).map((entry) => entry.time);
    expect(order[0]).toBe('14:01:30');
    expect(order[order.length - 1]).toBe('13:30:00');
  });

  test('a day of samples prints the newest and counts the rest', () => {
    const many = Array.from({ length: FEED_ROWS + 25 }, (_, index) => ({
      ...sampleBoard.activity[0],
      time: `13:${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 === 0 ? '00' : '30'}`,
    }));
    const html = render(createElement(ActivityFeed, { state: { activity: many, clientMatching: true } }));
    expect((html.match(/class="myx-feed-row"/g) ?? []).length).toBe(FEED_ROWS);
    expect(html).toContain('25 older');
  });
});

describe('the composer', () => {
  test('it says what stops the draft being saved', () => {
    expect(validateDraft(blankDraft('slot-a'))).toEqual(['No name', 'No repo', 'Slot 1 · No role', 'Slot 1 · No head']);
    const draft = draftOf(sampleBoard.team);
    expect(validateDraft(draft)).toEqual([]);
    expect(validateDraft({ ...draft, slots: draft.slots.map((slot) => ({ ...slot, lead: true })) }))
      .toContain('Exactly one slot must lead.');
    expect(validateDraft(bindSession(draft, 1, 'api-lead')))
      .toContain('api-lead · On two slots');
  });

  test('archiving sets a flag and deletes nothing', () => {
    const draft = draftOf(sampleBoard.team);
    const archived = setArchived(draft, true);
    expect(archived.archived).toBe(true);
    expect(archived.slots).toEqual(draft.slots);
    expect(archived.slots.filter((slot) => slot.session !== null)).toHaveLength(3);
    // And it round-trips: restoring gives back the team that was archived.
    expect(setArchived(archived, false)).toEqual(draft);
  });

  test('unbinding frees the seat and keeps the slot', () => {
    const draft = draftOf(sampleBoard.team);
    const free = unbindSession(draft, 0);
    expect(free.slots).toHaveLength(draft.slots.length);
    expect(free.slots[0].session).toBeNull();
    expect(free.slots[0].role).toBe('lead');
  });

  test('a new team is created, an existing one saved, and neither while the draft breaks a rule', () => {
    const blank = render(createElement(TeamCompose, {}));
    expect(blank).toContain('New team');
    // The blank draft breaks four rules, so its key is disabled: a save the daemon would refuse is
    // not offered.
    expect(blank).toMatch(/<button[^>]*disabled=""[^>]*>(?:(?!<\/button>)[\s\S])*Create team/);
    const existing = render(createElement(TeamCompose, { team: sampleBoard.team }));
    expect(existing).toContain('Edit storefront-api');
    expect(existing).toContain('Save team');
    expect(existing).not.toContain('Create team');
  });

  test('a slot picks its head and its session from what the daemon lists, and nobody types an id', () => {
    const heads = [{ value: 'claudex', label: 'claudex' }, { value: 'bonsai', label: 'claude-bonsai' }];
    const sessions = [{ value: 'sid-1', label: 'api-builder' }];
    const html = render(createElement(TeamCompose, { heads, sessions }));
    expect(html).toContain('Choose a head');
    expect(html).toContain('Open seat');
    // With nothing listed (not loaded yet, or a fixture) the fields fall back to typing.
    expect(render(createElement(TeamCompose, {}))).not.toContain('Choose a head');
  });

  test('a slot keeps a value the list no longer has, so opening an old team rewrites nothing', () => {
    const listed = [{ value: 'claudex', label: 'claudex' }];
    const blank = { value: '', label: 'Choose a head' };
    expect(optionsFor(listed, 'claudex', blank)).toEqual([blank, ...listed]);
    expect(optionsFor(listed, 'retired-head', blank)).toEqual([blank, ...listed, { value: 'retired-head', label: 'retired-head' }]);
    expect(optionsFor(listed, '', blank)).toEqual([blank, ...listed]);
  });
});

describe('a Teams dollar is an estimate, and says so', () => {
  // Marlin, 2026-09-25: the figures are rate-card prices of the tokens, and on a ChatGPT, Grok or
  // Kimi plan nobody pays them (CHANGELOG, TurnPrice.kt). Every place Teams prints a dollar carries
  // the kit's estimated basis, the word Usage prints over its cost chart.
  const dollars = (html: string): number => (html.match(/\$\d/g) ?? []).length;
  const tagged = (html: string): number => (html.match(/class="myx-basis">Estimated</g) ?? []).length;

  test('the cost figure, the members\' cost column, the cost per role and an opened seat', () => {
    const stats = render(createElement(TeamStats, { board: sampleBoard, data: sampleData }));
    expect(stats).toMatch(/myx-stat-label">API cost<span class="myx-basis">Estimated</);
    const members = render(createElement(TeamMembers, { board: sampleBoard, by: 'head' }));
    expect(members).toMatch(/>API cost<span class="myx-basis">Estimated<\/span><\/th>/);
    const roles = render(createElement(CostPerRole, { data: sampleData }));
    expect(dollars(roles)).toBeGreaterThan(0); // the denominator: a table with no dollars would pass vacuously
    expect(roles).toMatch(/>API cost<span class="myx-basis">Estimated<\/span><\/th>/);
    const seat = seatsOf(sampleBoard).find((candidate) => candidate.member !== null && candidate.member.costEst !== null);
    expect(seat).toBeDefined();
    if (seat === undefined) return;
    const detail = render(createElement(SeatDetail, { board: sampleBoard, seat }));
    expect(dollars(detail)).toBe(tagged(detail));
  });
});

describe('an empty chat says what it cannot show', () => {
  test('a plain claude member\'s own messages never reach the chat, and the empty says so', () => {
    const html = render(createElement(TeamChat, { state: { messages: [] } }));
    expect(html).toContain('No messages today');
    expect(html).toContain("plain claude members' sends do not");
  });
});
