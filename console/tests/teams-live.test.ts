// THE TEAMS PAGE AGAINST THE DAEMON'S OWN READS (row M4-01). The board is composed, not fetched
// (there is no GET /api/teams/{id}), so every join below is a place a live board could print one
// session's work under another's name, drop a message, or save a team the operator did not write.
// Each test holds one join against fixed payloads, and the save path against a stubbed fetch that
// records what went on the wire.
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';

import type { SessionRow } from '../src/entities/session';
import type { InflightTurn, TurnRow } from '../src/entities/perf';
import { fetchPerfTurns } from '../src/entities/perf';
import { perfTurnsStore } from '../src/entities/perf/model/store';
import type { TeamActivityPayload, TeamChatPayload, TeamEconomicsPayload, TeamPanels, TeamRow, TeamSlot } from '../src/entities/team';
import { fetchTeamPanels } from '../src/entities/team';
import { draftOf, keyFor, saveDraft, unbindSession, unbindsOf, writeOf } from '../src/features/team-compose';
import { UNLISTED, activityOf, boardOf, dayOf, dayStartOf, hhmm, hhmmss, lastHourOf, liveTurnsOf, membersOf, messagesOf, turnsOf, viewDataOf } from '../src/pages/teams/board';
import { dayAxis } from '../src/widgets/team-board';
import { panelStates, teamsBodyFor, turnLogOf } from '../src/pages/teams';
import type { TurnLog } from '../src/pages/teams';

const NOW = Date.UTC(2026, 8, 23, 14, 0, 0);
const DAY = Date.UTC(2026, 8, 23);

const slot = (id: string, role: string, session: string | null, history: string[] = session === null ? [] : [session]): TeamSlot => ({
  id,
  role,
  head: role === 'lead' ? 'claude' : 'claudex',
  model: role === 'lead' ? 'fable' : null,
  account: role === 'lead' ? 'acct-a' : null,
  lead: role === 'lead',
  instructions: role === 'lead' ? 'drive the packet' : null,
  session,
  instructions_updated_epoch_millis: null,
  sessions_history: history,
});

const TEAM: TeamRow = {
  id: 'team-1',
  name: 'wire',
  goal: 'ship the wiring',
  features: ['teams'],
  repo: '/repo',
  archived: false,
  created_epoch_millis: DAY,
  updated_epoch_millis: DAY,
  slots: [
    slot('s-lead', 'lead', 'aaaaaaaa-1111'),
    // Rebound: its tally is two sessions' work, and must not print on the current one's strip.
    slot('s-build', 'builder', 'bbbbbbbb-2222', ['zzzzzzzz-0000', 'bbbbbbbb-2222']),
    slot('s-review', 'reviewer', null),
  ],
  idempotency_key: null,
  create_fingerprint: null,
};

const registry = (session: string, fields: Partial<SessionRow>): SessionRow => ({
  pid: 1,
  session_id: session,
  name: null,
  kind: 'interactive',
  version: '2',
  cwd: '/repo',
  status: 'busy',
  status_updated_at: NOW,
  started_at: NOW - 2 * 3_600_000 - 5 * 60_000,
  updated_at: NOW,
  address: null,
  head: 'claude',
  availability: 'live',
  ...fields,
});

const tally = (turns: number) => ({
  turns,
  tokens: { input: 100, cache_read: 1000, cache_write: 10, output: 50 },
  cost_usd: 0.5,
  unpriced_turns: 0,
  last_turn_at_epoch_millis: NOW - 60_000,
});

const ECONOMICS: TeamEconomicsPayload = {
  team_id: 'team-1',
  heads_read: ['claude', 'claudex'],
  unattributed_turns: 2,
  oldest_turn_epoch_millis: DAY,
  roles: [{ role: 'lead', ...tally(3) }, { role: 'builder', ...tally(9) }],
  slots: [
    { slot: 's-lead', ...tally(3), checks: 'pass', checks_source: 'outcome' },
    { slot: 's-build', ...tally(9), checks: 'fail', checks_source: 'outcome' },
  ],
};

const CHAT: TeamChatPayload = {
  team_id: 'team-1',
  day_start_epoch_millis: DAY,
  packet_note: 'no packet on the wire',
  messages: [
    { at: NOW - 600_000, from: 'aaaaaaaa-1111', from_slot: 's-lead', from_head: 'claude', to: 'uds:/tmp/b.sock', to_slot: 's-build', packet: null, text: 'build it', text_source: '/t.jsonl', missing_reason: null },
    { at: NOW - 300_000, from: 'cccccccc-9999', from_slot: null, from_head: null, to: 'someone-else', to_slot: null, packet: null, text: null, text_source: null, missing_reason: 'no transcript for the sender in ~/.claude/projects' },
  ],
};

const ACTIVITY: TeamActivityPayload = {
  team_id: 'team-1',
  day_start_epoch_millis: DAY,
  sample_interval_note: '30 s',
  upstream_label_queries: 4,
  entries: [{ at: NOW - 30_000, session: 'aaaaaaaa-1111', slot: 's-lead', head: 'claude', label: 'editing', detail: null }],
};

const PANELS: TeamPanels = { teamId: 'team-1', chat: CHAT, activity: ACTIVITY, economics: ECONOMICS };

const SESSIONS = [registry('aaaaaaaa-1111', { name: 'lead-seat' })];

describe('the members', () => {
  const members = membersOf(TEAM, SESSIONS, ECONOMICS, NOW);

  test('one per BOUND slot, keyed on the slot, named as the registry names it', () => {
    expect(members.map((m) => [m.slot, m.name, m.sessionId])).toEqual([
      ['s-lead', 'lead-seat', 'aaaaaaaa-1111'],
      ['s-build', 'bbbbbbbb-2222', 'bbbbbbbb-2222'],
    ]);
  });

  test("the registry gives the state, start, uptime and workspace, and an unlisted session says so", () => {
    expect(members[0]).toMatchObject({ state: 'busy', created: hhmmss(NOW - 2 * 3_600_000 - 5 * 60_000), uptime: '2h 5m', workspace: '/repo' });
    expect(members[0].startedAt).toBe(SESSIONS[0].started_at); // the epoch the lanes order it by
    expect(members[0].startedAt).not.toBeNull();
    expect(members[1]).toMatchObject({ state: UNLISTED, created: null, startedAt: null, uptime: null, workspace: null });
    const gone = membersOf(TEAM, [registry('aaaaaaaa-1111', { availability: 'gone' })], ECONOMICS, NOW)[0];
    expect(gone.state).toBe('gone');
    expect(gone.uptime).toBeNull();
  });

  test("a slot's tally prints on its session only when the slot has held no other session", () => {
    expect(members[0]).toMatchObject({ turns: 3, tokensIn: 1110, tokensOut: 50, costEst: 0.5, checks: 'pass', lastTurn: hhmm(NOW - 60_000) });
    // s-build held zzzzzzzz before: its 9 turns are not bbbbbbbb's alone.
    expect(members[1]).toMatchObject({ turns: null, tokensIn: null, tokensOut: null, costEst: null, checks: null, lastTurn: null });
  });

  test("a slot on a head the daemon did not read has no tally: splice never saw its turns (Marlin, 2026-09-25)", () => {
    // The daemon still answers the slot, with zeros it never measured: a plain `claude` architect
    // read $0.000 and 0 turns beside members splice does see.
    const unread = membersOf(TEAM, SESSIONS, { ...ECONOMICS, heads_read: ['claudex'] }, NOW);
    expect(unread[0]).toMatchObject({ head: 'claude', turns: null, tokensIn: null, tokensOut: null, costEst: null, checks: null, lastTurn: null });
  });

  test('a figure no route reports is null, never a zero', () => {
    for (const member of members) {
      expect(member.window).toBeNull();
      expect(member.contextLeftPct).toBeNull();
      expect(member.branch).toBeNull();
      expect(member.diff).toBeNull();
    }
  });

  test('a member is lead by its slot flag, never by its role name', () => {
    // compose lets a role be any text, so the flag and the name disagree here; on TEAM they agree
    // on every slot, and a membersOf that keyed on the role would pass every other test.
    const team: TeamRow = {
      ...TEAM,
      slots: [
        { ...slot('s-arch', 'architect', 'aaaaaaaa-1111'), lead: true },
        { ...slot('s-named', 'lead', 'bbbbbbbb-2222'), lead: false },
      ],
    };
    expect(membersOf(team, SESSIONS, ECONOMICS, NOW).map((m) => [m.slot, m.lead])).toEqual([
      ['s-arch', true],
      ['s-named', false],
    ]);
  });
});

describe('the messages and the activity', () => {
  const members = membersOf(TEAM, SESSIONS, ECONOMICS, NOW);

  test('the sender resolves by session and the recipient by slot; a stranger keeps its own string', () => {
    const messages = messagesOf(members, CHAT) ?? [];
    expect(messages.map((m) => [m.time, m.from, m.to, m.fromHead])).toEqual([
      [hhmm(NOW - 600_000), 'lead-seat', 'bbbbbbbb-2222', 'claude'],
      [hhmm(NOW - 300_000), 'cccccccc-9999', 'someone-else', '–'],
    ]);
  });

  test('a text the daemon could not read prints its reason, and no packet is invented', () => {
    const messages = messagesOf(members, CHAT) ?? [];
    expect(messages[0].text).toBe('build it');
    expect(messages[1].text).toBe('text not read: no transcript for the sender in ~/.claude/projects');
    expect(messages.every((m) => m.packet === '–')).toBe(true);
  });

  test('a chat or an activity not read yet is null, never an empty day (Hitstop, 2026-09-25)', () => {
    expect(messagesOf(members, null)).toBeNull();
    expect(activityOf(members, null)).toBeNull();
    expect(messagesOf(members, { ...CHAT, messages: [] })).toEqual([]);
  });

  test('a sample lands under its member', () => {
    expect(activityOf(members, ACTIVITY)).toEqual([{ time: hhmmss(NOW - 30_000), member: 'lead-seat', activity: 'editing', detail: '' }]);
  });

  test("panels answered for ANOTHER team are not this team's", () => {
    const board = boardOf(TEAM, SESSIONS, { ...PANELS, teamId: 'team-2' }, NOW, null);
    expect(board.messages).toBeNull();
    expect(board.members[0].turns).toBeNull();
    expect(viewDataOf(board, [], [], { ...PANELS, teamId: 'team-2' }, NOW)).toBeNull();
    expect(panelStates(board, { ...PANELS, teamId: 'team-2' }).chat).toBeNull();
  });

  test("a panel that failed is the widget's error, not an empty list", () => {
    const board = boardOf(TEAM, SESSIONS, { ...PANELS, chat: { error: 'HTTP 500' } }, NOW, null);
    const states = panelStates(board, { ...PANELS, chat: { error: 'HTTP 500' } });
    expect(states.chat).toEqual({ error: 'HTTP 500' });
    expect(states.feed).toMatchObject({ clientMatching: true });
  });
});

describe('the turn log', () => {
  const members = membersOf(TEAM, SESSIONS, ECONOMICS, NOW);
  const row = (session: string | undefined, ts: number, total: number, tokens = true): TurnRow => ({
    head: 'claude',
    ts,
    model: 'fable',
    outcome: 'ok',
    compact: false,
    ...(session === undefined ? {} : { session }),
    total,
    // A row's in_tokens already holds its cache reads and writes (PerfKeys.kt IN_TOKENS).
    ...(tokens ? { in_tokens: 111, cached_tokens: 100, cache_write_tokens: 1, out_tokens: 7 } : {}),
  });
  const ROWS = [
    row('aaaaaaaa', NOW - 120_000, 90_000),
    row('bbbbbbbb', NOW - 30 * 60_000, 5 * 60_000, false),
    row('bbbbbbbb', DAY - 60_000, 1_000),
    row('dddddddd', NOW - 60_000, 1_000),
    row(undefined, NOW - 60_000, 1_000),
  ];

  test("the day's turns of the team's sessions, joined on the 8-character tag, oldest first", () => {
    expect(turnsOf(members, ROWS, DAY).map((t) => [t.member, t.start, t.ms, t.input, t.output])).toEqual([
      ['bbbbbbbb-2222', NOW - 35 * 60_000, 5 * 60_000, null, null],
      ['lead-seat', NOW - 210_000, 90_000, 111, 7],
    ]);
  });

  test('a turn is its input as the row counts it, cache reads and writes already inside', () => {
    // Summing them again doubled a cached turn's input (the row carried 111, the board printed 212).
    expect(turnsOf(members, ROWS, DAY)[1].input).toBe(111);
  });

  test('the last hour counts a turn from its start to its end', () => {
    const hour = lastHourOf(members, ROWS, [], NOW);
    expect(hour).toHaveLength(61);
    expect(hour[0].at).toBe(hhmm(NOW - 60 * 60_000));
    expect(hour[60].at).toBe(hhmm(NOW));
    const at = (minutesAgo: number) => hour.find((point) => point.at === hhmm(NOW - minutesAgo * 60_000))?.turns;
    expect(at(36)).toBe(0);
    expect(at(34)).toBe(1);
    expect(at(3)).toBe(1);
    expect(at(1)).toBe(0);
  });
});

describe('the clock the page prints', () => {
  // Marlin, 2026-09-25: every Teams time was UTC beside the rule's local clock, with the word only
  // behind the timeline's info tip. The times are the operator's; the day read stays the daemon's.
  const zone = process.env.TZ;
  afterEach(() => {
    if (zone === undefined) delete process.env.TZ;
    else process.env.TZ = zone;
  });

  test("times print on the operator's own clock, and so does the day's window (V4-249)", () => {
    process.env.TZ = 'America/Chicago';
    expect(hhmm(NOW)).toBe('09:00'); // 14:00 UTC, in September's CDT
    expect(hhmmss(NOW - 30_000)).toBe('08:59:30');
    expect(dayStartOf(NOW)).toBe(DAY + 5 * 3_600_000); // local midnight, 05:00 UTC
    expect(dayAxis([NOW - 5 * 3_600_000], NOW).ticks[0].label).toBe('04:00');
  });

  test('the axis ticks stand on the local hour where the zone is off the hour', () => {
    process.env.TZ = 'Asia/Kolkata';
    expect(hhmm(NOW)).toBe('19:30');
    const ticks = dayAxis([NOW - 5 * 3_600_000], NOW).ticks;
    expect(ticks.length).toBeGreaterThan(1);
    expect(ticks.every((tick) => tick.label.endsWith(':00'))).toBe(true);
  });
});

describe("the day is the viewer's own (V4-249)", () => {
  // In Chicago the board turned over at 19:00 CDT, 00:00 UTC, and a take that crossed it lost its
  // first half. 20:00 CDT on Sep 25 is 01:00 UTC on the 26th; the local day began at 05:00 UTC.
  const zone = process.env.TZ;
  afterEach(() => {
    if (zone === undefined) delete process.env.TZ;
    else process.env.TZ = zone;
  });
  const EVENING = Date.UTC(2026, 8, 26, 1, 0, 0);
  const LOCAL_MIDNIGHT = Date.UTC(2026, 8, 25, 5, 0, 0);
  const SIX_PM = Date.UTC(2026, 8, 25, 23, 0, 0);
  const row = (ts: number, total: number): TurnRow => ({ head: 'claude', ts, model: 'fable', outcome: 'ok', compact: false, session: 'aaaaaaaa', total });

  test('a board held at 20:00 CDT keeps the evening before UTC midnight', () => {
    process.env.TZ = 'America/Chicago';
    expect(dayStartOf(EVENING)).toBe(LOCAL_MIDNIGHT);
    const board = boardOf(TEAM, SESSIONS, PANELS, EVENING, null);
    const rows = [row(LOCAL_MIDNIGHT - 1, 1), row(SIX_PM, 60_000), row(EVENING - 60_000, 30_000)];
    expect(viewDataOf(board, rows, [], PANELS, EVENING)?.turns.map((turn) => turn.start)).toEqual([SIX_PM - 60_000, EVENING - 90_000]);
  });

  test("the chat and activity reads ask the daemon for the viewer's day, from local midnight to the next", async () => {
    process.env.TZ = 'America/Chicago';
    const urls: string[] = [];
    vi.stubGlobal('fetch', (url: string) => {
      urls.push(url);
      return Promise.resolve(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    try {
      await fetchTeamPanels('team-1', dayOf(EVENING));
    } finally {
      vi.unstubAllGlobals();
    }
    const day = `?from=${LOCAL_MIDNIGHT}&to=${Date.UTC(2026, 8, 26, 5, 0, 0)}`;
    expect(urls.filter((url) => url.includes('/chat') || url.includes('/activity'))).toEqual([
      `/api/teams/team-1/chat${day}`,
      `/api/teams/team-1/activity${day}`,
    ]);
  });

  test('a DST day is the calendar\'s, 25 hours on the fall-back, never 24', () => {
    process.env.TZ = 'America/Chicago';
    const fallBack = dayOf(Date.UTC(2026, 10, 1, 18, 0, 0)); // Nov 1 2026, noon CST
    expect(fallBack.to - fallBack.from).toBe(25 * 3_600_000);
  });
});

describe('the turns running now', () => {
  // Marlin, 2026-09-25: In flight printed 0 and no turn ever read Running, because the board counted
  // landed perf rows only (a turn has no row until it ends) and stamped every turn `live: false`.
  const members = membersOf(TEAM, SESSIONS, ECONOMICS, NOW);
  const slot = (label: string, ageMs: number, head = 'claude'): InflightTurn => ({
    head, label, compact: false, phase: 'streaming', ageMs, idleMs: 0, streamIdleMs: 300_000,
  });
  const GATES = [
    slot('aaaaaaaa fable', 5 * 60_000),
    slot('bbbbbbbb gpt-5', 90_000, 'claudex'),
    slot('dddddddd fable', 60_000), // a session of no member
    slot('fable', 60_000), // a client that sent no session
    slot('req', 1_000), // a request not read yet
  ];

  test("each gate slot lands under its member on the session tag, and no other slot is the team's", () => {
    expect(liveTurnsOf(members, GATES, NOW).map((t) => [t.member, t.start, t.ms, t.live])).toEqual([
      ['lead-seat', NOW - 5 * 60_000, 5 * 60_000, true],
      ['bbbbbbbb-2222', NOW - 90_000, 90_000, true],
    ]);
  });

  test('the count is the running turns, and it is unknown, not none, before the heads answer', () => {
    const board = boardOf(TEAM, SESSIONS, PANELS, NOW, null);
    const data = viewDataOf(board, [], GATES, PANELS, NOW);
    expect(data?.inFlight).toBe(2);
    expect(data?.turns.filter((t) => t.live)).toHaveLength(2);
    expect(viewDataOf(board, [], [], PANELS, NOW)?.inFlight).toBe(0);
    expect(viewDataOf(board, [], null, PANELS, NOW)?.inFlight).toBeNull();
  });

  test('the last hour counts a running turn from its start to now', () => {
    const hour = lastHourOf(members, [], liveTurnsOf(members, GATES, NOW), NOW);
    const at = (minutesAgo: number) => hour.find((point) => point.at === hhmm(NOW - minutesAgo * 60_000))?.turns;
    expect(at(6)).toBe(0);
    expect(at(5)).toBe(1);
    expect(at(1)).toBe(2);
    expect(at(0)).toBe(2);
  });
});

describe('the turn log says when it is short or failed (V4-288)', () => {
  // The page drew turns.data.landed alone: a failed read kept the last log (or "No turns today") with
  // no word, a head the route clamped read as a head with a quiet morning, and the merge cut the day
  // to 2,000 rows across every head, so past 2,000 fleet turns the day began partway through.
  afterEach(() => vi.unstubAllGlobals());
  const TIMELINE = { layout: 'timeline', group: null };
  const board = boardOf(TEAM, SESSIONS, PANELS, NOW, null);
  const start = dayStartOf(NOW);
  const wire = (session: string, ts: number) => ({ ts, model: 'fable', outcome: 'ok', compact: false, session, account: null, cache_cold: null, total: 1_000 });
  /** `count` turns of one session, oldest first, spread over the day from `from`. */
  const day = (session: string, count: number, from: number, step: number) => Array.from({ length: count }, (_, i) => wire(session, from + i * step));
  const block = (key: string, rows: readonly object[], over: object = {}) => ({
    key, label: key, count: rows.length, returned: rows.length, truncated: false, oldest_held_ts: null, rows, ...over,
  });
  /** The route's answer for one head, or a failed request for it. */
  type Answer = ReturnType<typeof block> | { key: string; label: string; error: string };
  const answer = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
  /** The page's own read (index.tsx: every head, n=2,000, since local midnight) against a stubbed
   *  daemon; a null `since` is the Turns page's tail read, which sends none. */
  const read = async (answers: readonly Answer[], n = 2_000, since: number | null = start): Promise<TurnLog> => {
    vi.stubGlobal('fetch', (url: string) => {
      const at = new URL(url, 'http://console');
      if (at.pathname === '/api/heads') return answer({ heads: answers.map(({ key }) => ({ key, running: true, gate: null })) });
      return answer({ since: since ?? 0, n, heads: answers.filter(({ key }) => key === at.searchParams.get('head')) });
    });
    await fetchPerfTurns(undefined, n, since ?? undefined);
    return turnLogOf(perfTurnsStore.get());
  };
  const timeline = (log: TurnLog): string => renderToStaticMarkup(teamsBodyFor({
    view: TIMELINE, teams: null, board, data: viewDataOf(board, log.rows, [], PANELS, NOW), faults: log.faults,
  }));
  const STEP = Math.floor((NOW - 60_000 - start) / 2_100);

  test('a turns read that fails keeps the last log and says it is stale', async () => {
    const good = await read([block('claude', day('aaaaaaaa', 3, start + 10_000, STEP))]);
    vi.stubGlobal('fetch', () => Promise.reject(new TypeError('Failed to fetch')));
    await fetchPerfTurns(undefined, 2_000, start);
    const log = turnLogOf(perfTurnsStore.get());
    expect(log.rows).toEqual(good.rows);
    const html = timeline(log);
    expect(html).toContain('Turn log: Splice is not answering.');
    expect(html).toContain('<span class="myx-fig-basis">Stale</span>');
  });

  test('a first turns read that fails is a fault, never "No turns today"', () => {
    const html = timeline(turnLogOf({ data: null, error: 'Splice is not answering.', loading: false, lastUpdated: null }));
    expect(html).toContain('Turn log: Splice is not answering.');
    expect(html).not.toContain('No turns today');
  });

  test('a daemon that does not serve the turn log says so, never "No turns today"', () => {
    const html = timeline(turnLogOf({ data: { pending: 'V4-127' }, error: null, loading: false, lastUpdated: null }));
    expect(html).toContain('This splice version does not serve the turn log.');
    expect(html).not.toContain('No turns today');
  });

  test('a head the daemon could not read is named on the timeline', async () => {
    const html = timeline(await read([
      block('claude', day('aaaaaaaa', 3, start + 10_000, STEP)),
      { key: 'claudex', label: 'claudex', error: 'perf file unreadable: permission denied' },
    ]));
    expect(html).toContain('claudex: perf file unreadable: permission denied');
  });

  test('a head the route clamped says how much of its day was read', async () => {
    const log = await read([block('claude', day('aaaaaaaa', 2_000, start + 10_000, STEP), { count: 2_345, truncated: true })]);
    expect(timeline(log)).toContain('claude: 2,000 of 2,345 turns read');
  });

  test('2,001 turns across two heads are the whole day: the cap is per head', async () => {
    const log = await read([
      block('claude', day('aaaaaaaa', 1_500, start + 10_000, STEP)),
      block('claudex', day('bbbbbbbb', 501, start + 20_000 + 1_500 * STEP, STEP)),
    ]);
    expect(log.rows).toHaveLength(2_001);
    const html = timeline(log);
    expect(html).toContain('aria-label="lead-seat: 1500 turns, 0 running"');
    expect(html).toContain('aria-label="bbbbbbbb-2222: 501 turns, 0 running"');
    expect(html).not.toContain('myx-fault');
  });

  test("the Turns page's tail read is still the fleet's newest n", async () => {
    const log = await read([
      block('claude', day('aaaaaaaa', 150, start + 10_000, STEP)),
      block('claudex', day('bbbbbbbb', 150, start + 20_000, STEP)),
    ], 200, null);
    expect(log.rows).toHaveLength(200);
    expect(log.rows.at(-1)?.ts).toBe(start + 20_000 + 149 * STEP);
  });
});

describe('the save', () => {
  afterEach(() => vi.unstubAllGlobals());

  const record = (answer: (url: string, init: RequestInit) => unknown) => {
    const calls: { url: string; method: string; headers: Record<string, string>; body: unknown }[] = [];
    vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
      calls.push({ url, method: init.method ?? 'GET', headers: init.headers as Record<string, string>, body: JSON.parse(String(init.body)) });
      return Promise.resolve(new Response(JSON.stringify(answer(url, init)), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    return calls;
  };

  test('the body carries every slot whole: model and account kept, blank instructions cleared', () => {
    const draft = draftOf(TEAM);
    const body = writeOf({ ...draft, name: '  wire  ', slots: draft.slots.map((s) => ({ ...s, instructions: s.id === 's-lead' ? '   ' : s.instructions })) });
    expect(body.name).toBe('wire');
    expect(body.slots[0]).toEqual({ id: 's-lead', role: 'lead', head: 'claude', model: 'fable', account: 'acct-a', lead: true, instructions: null, session: 'aaaaaaaa-1111' });
    expect(body.features).toEqual(['teams']);
  });

  test('a create goes to PUT /api/teams under an Idempotency-Key', async () => {
    const calls = record(() => ({ ...TEAM, id: 'team-new' }));
    const saved = await saveDraft(draftOf(TEAM), null, 'key-1');
    expect(saved.id).toBe('team-new');
    expect(calls.map((c) => [c.method, c.url, c.headers['Idempotency-Key']])).toEqual([['PUT', '/api/teams', 'key-1']]);
  });

  test('an edit replaces, and a seat the draft opened is unbound through its own write', async () => {
    const calls = record(() => TEAM);
    await saveDraft(unbindSession(draftOf(TEAM), 0), TEAM, 'unused');
    expect(calls.map((c) => [c.method, c.url])).toEqual([['PUT', '/api/teams/team-1'], ['PUT', '/api/teams/team-1/sessions']]);
    expect(calls[1].body).toEqual({ bindings: { 's-lead': null } });
  });

  test('an edit that opens no seat is one write', async () => {
    const calls = record(() => TEAM);
    await saveDraft(draftOf(TEAM), TEAM, 'unused');
    expect(calls.map((c) => c.url)).toEqual(['/api/teams/team-1']);
    expect(unbindsOf(TEAM, draftOf(TEAM))).toEqual({});
  });

  test('a retry of the same body reuses its key; a changed body gets a new one', () => {
    let n = 0;
    const mint = () => `k${++n}`;
    const first = keyFor(null, 'a', mint);
    expect(keyFor(first, 'a', mint)).toBe(first);
    expect(keyFor(first, 'b', mint)).toEqual({ body: 'b', key: 'k2' });
  });
});
