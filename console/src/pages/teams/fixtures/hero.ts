// The comp's own board, as data. Every word here is copied from the approved
// comp (.impeccable/mocks/team-board-a.png) — the operator approved that screen
// with those words, so the hero reproduces them rather than inventing its own.
//
// It is a FIXTURE: it loads only when import.meta.env.DEV is true and the
// address carries ?fixture=hero (CONTRACTS.md section 4). The shipped dist
// carries none of these bytes, which the row's note proves by grepping the
// built file.
import type { TeamActivity, TeamEconomicsPayload, TeamMemberRow, TeamMessage, TeamPayload, TeamRow, TeamSlot } from '@entities/team';

/** A slot as the daemon writes one, with the fields the comp does not print left at their empty. */
const slot = (id: string, fields: Pick<TeamSlot, 'role' | 'head' | 'model' | 'account' | 'lead' | 'session'>): TeamSlot => ({
  id,
  ...fields,
  instructions: null,
  instructions_updated_epoch_millis: null,
  sessions_history: fields.session === null ? [] : [fields.session],
});

const TEAM: TeamRow = {
  id: 'team_7f2c1b4a',
  name: 'storefront-api',
  goal: 'promoter bookkeeping lives on fly raw',
  features: [],
  repo: '~/Documents/dev/infra/storefront-api',
  archived: false,
  created_epoch_millis: Date.UTC(2025, 4, 22, 9, 12, 33),
  updated_epoch_millis: Date.UTC(2025, 4, 22, 14, 2, 5),
  slots: [
    slot('slot-lead', { role: 'lead', head: 'claude', model: 'fable', account: 'acct-a', lead: true, session: 'gs-backend-claude' }),
    slot('slot-builder-1', { role: 'builder', head: 'claude-deepseek', model: 'deepseek-flash', account: 'acct-a', lead: false, session: 'gs-backend-builder' }),
    slot('slot-builder-2', { role: 'builder', head: 'claude', model: 'fable', account: 'acct-b', lead: false, session: 'gs-backend-builder2' }),
  ],
  idempotency_key: null,
  create_fingerprint: null,
};

// The comp racks two sessions: the lead on head claude and one builder on head
// claude-deepseek. The team's third slot is bound (the header says 3 bound) but its
// session is not racked in this viewport, so it is not a member row here.
const MEMBERS: TeamMemberRow[] = [
  {
    slot: 'slot-lead',
    name: 'gs-backend-claude',
    role: 'lead',
    head: 'claude',
    model: 'fable',
    account: 'acct-a',
    window: '5h 74%',
    lastTurn: '14:01',
    state: 'driving',
    sessionId: 's-1a7c9e2d',
    created: '09:12:33',
    uptime: '4h 49m',
    turns: 27,
    tokensIn: 182341,
    tokensOut: 71552,
    costEst: 0.412,
    contextLeftPct: 35,
    scratchpadKb: 12.8,
    workspace: '~/.../storefront-api',
    branch: 'feature/gs-41',
    base: 'main',
    diff: '+412 -37',
    checks: 'pass',
  },
  {
    slot: 'slot-builder-1',
    name: 'gs-backend-builder',
    role: 'builder',
    head: 'claude-deepseek',
    model: 'deepseek-flash',
    account: 'acct-a',
    window: null,
    lastTurn: '13:58',
    state: 'building GS-41 done',
    sessionId: 's-5f3b8a11',
    created: '09:18:02',
    uptime: '4h 44m',
    turns: 18,
    tokensIn: 93274,
    tokensOut: 38611,
    costEst: 0.183,
    contextLeftPct: null,
    scratchpadKb: 8.1,
    workspace: '~/.../storefront-api',
    branch: 'feature/gs-41',
    base: 'main',
    diff: '+289 -37',
    checks: 'pass',
  },
];

const MESSAGES: TeamMessage[] = [
  {
    time: '13:41',
    from: 'gs-backend-claude',
    to: 'gs-backend-builder',
    packet: 'GS-41',
    text: 'packet GS-41: promoter bookkeeping lives on fly raw. build, run parity, report',
    fromHead: 'claude',
  },
  {
    time: '13:58',
    from: 'gs-backend-builder',
    to: 'gs-backend-claude',
    packet: 'GS-41',
    text: 'GS-41 done, see ledger',
    fromHead: 'claude-deepseek',
  },
  {
    time: '14:01',
    from: 'gs-backend-claude',
    to: 'gs-backend-builder2',
    packet: 'GS-42',
    text: 'packet GS-42: dearm the machine-update schedule',
    fromHead: 'claude',
  },
];

const ACTIVITY: TeamActivity[] = [
  { time: '14:01:30', member: 'gs-backend-claude', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '14:01:30', member: 'gs-backend-builder', activity: 'running checks/gate.sh', detail: 'checks/gate.sh --all' },
  { time: '14:01:30', member: 'gs-backend-builder2', activity: 'searching for fly_raw', detail: 'rg -n "fly_raw"' },
  { time: '14:02:00', member: 'gs-backend-builder', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '14:02:00', member: 'gs-backend-claude', activity: 'messaging a peer session', detail: 'to gs-backend-builder2' },
];

/** The whole first viewport, as the comp draws it. */
export const heroBoard: TeamPayload = {
  team: TEAM,
  members: MEMBERS,
  messages: MESSAGES,
  activity: ACTIVITY,
  coldCacheHint: false,
};

/** The hand-off the board draws between the bays: the newest message edge. */
export const heroHandoff: TeamMessage = MESSAGES[2];

// ---- the other two views (comps team-board-b and team-board-c) ---------------------------------
//
// Same team, same fixture file, its own payload: the hero fixture IS comp-a and must keep printing
// comp-a's words, so the by-role and timeline views carry the words THEIR comps print instead of
// editing the hero's. Every string below is read off team-board-b.png or team-board-c.png.
//
// TWO PLACES THE COMPS DISAGREE WITH THEMSELVES, resolved by deriving rather than by copying, and
// named here so the next reader does not take it for a slip:
//   1. comp-b draws a reviewer bay with no session while its header still reads `3 slots, 3 bound`.
//      A reviewer slot IS a slot, so this fixture declares four and the header derives
//      `4 slots, 3 bound` from the data it is given.
//   2. comp-c's cost table says the builder role took 8 turns while its own bar chart gives
//      gs-backend-builder 8 and gs-backend-builder2 5. The two cannot both be read off one set of
//      turns, so the TOKEN sums are the comp's exactly and the turn counts are the bars', which
//      the daemon's role tally sums (8 + 5).
import type { TeamTurn, TeamViewData } from '@widgets/team-board';

const VIEW_TEAM: TeamRow = {
  ...TEAM,
  slots: [
    ...TEAM.slots.slice(0, 2),
    slot('slot-builder-2', { role: 'builder', head: 'claude-deepseek', model: 'deepseek-flash', account: 'acct-a', lead: false, session: 'gs-backend-builder2' }),
    // comp-b: the reviewer bay is empty and names the head an operator would launch into it.
    slot('slot-reviewer', { role: 'reviewer', head: 'claudex', model: null, account: null, lead: false, session: null }),
  ],
};

const VIEW_MEMBERS: TeamMemberRow[] = [
  MEMBERS[0],
  { ...MEMBERS[1], state: 'GS-41 done' },
  {
    ...MEMBERS[1],
    slot: 'slot-builder-2',
    name: 'gs-backend-builder2',
    state: 'reading GS-42',
    sessionId: 's-9d04c3e7',
    created: '13:59:40',
    uptime: '2m 25s',
    lastTurn: '14:00',
    turns: 5,
    tokensIn: 72883,
    tokensOut: 28931,
    costEst: 0.141,
    scratchpadKb: 2.4,
    diff: '+61 -4',
  },
];

const VIEW_ACTIVITY: TeamActivity[] = [
  { time: '13:30', member: 'gs-backend-claude', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '13:30', member: 'gs-backend-builder', activity: 'searching for fly_raw', detail: 'rg -n "fly_raw"' },
  { time: '13:30', member: 'gs-backend-builder2', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '13:34', member: 'gs-backend-builder2', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '13:39', member: 'gs-backend-claude', activity: 'running checks/gate.sh', detail: 'checks/gate.sh --all' },
  { time: '13:39', member: 'gs-backend-builder', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '13:40', member: 'gs-backend-builder2', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '13:44', member: 'gs-backend-builder2', activity: 'searching for fly_raw', detail: 'rg -n "fly_raw"' },
  { time: '13:50', member: 'gs-backend-claude', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '13:50', member: 'gs-backend-builder', activity: 'running checks/gate.sh', detail: 'checks/gate.sh --all' },
  { time: '13:50', member: 'gs-backend-builder2', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '13:55', member: 'gs-backend-claude', activity: 'messaging a peer session', detail: 'to gs-backend-builder2' },
  { time: '13:55', member: 'gs-backend-builder', activity: 'running checks/gate.sh', detail: 'checks/gate.sh --all' },
  { time: '14:00', member: 'gs-backend-claude', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '14:00', member: 'gs-backend-builder', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '14:00', member: 'gs-backend-builder2', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
];

const VIEW_TURNS: TeamTurn[] = [
  { id: 't-1821', member: 'gs-backend-claude', time: '13:33', duration: '14m 12s', input: 42781, output: 18243, live: false },
  { id: 't-1822', member: 'gs-backend-builder', time: '13:34', duration: '11m 07s', input: 31209, output: 12884, live: false },
  { id: 't-1823', member: 'gs-backend-claude', time: '13:43', duration: '16m 30s', input: 48923, output: 20112, live: false },
  { id: 't-1824', member: 'gs-backend-builder', time: '13:44', duration: '19m 55s', input: 61332, output: 24991, live: false },
  { id: 't-1825', member: 'gs-backend-builder2', time: '13:53', duration: '14m 32s', input: 37775, output: 15421, live: false },
  { id: 't-1826', member: 'gs-backend-claude', time: '14:00', duration: '2m 11s', input: 6412, output: 2301, live: true },
  { id: 't-1827', member: 'gs-backend-builder', time: '14:00', duration: '1m 37s', input: 4812, output: 1874, live: true },
  { id: 't-1828', member: 'gs-backend-builder2', time: '13:59', duration: '3m 05s', input: 7923, output: 2945, live: true },
];

/** A lifetime tally with no cache traffic, as TeamsEconomics.kt writes one. */
const tally = (turns: number, input: number, output: number, cost: number, lastTurn: number) => ({
  turns,
  tokens: { input, cache_read: 0, cache_write: 0, output },
  cost_usd: cost,
  unpriced_turns: 0,
  last_turn_at_epoch_millis: lastTurn,
});

/** The daemon's own words for where a slot's `checks` came from (TeamsEconomics.kt CHECKS_SOURCE). */
const CHECKS_SOURCE = "the outcome tag of the slot's most recently tallied turn (PerfRow.outcome)";

/** GET /api/teams/{id}/economics for the views' team: the comp's token sums per role, and its bars'
 *  turn counts per slot. The reviewer slot has held no session and so has no tally. */
const VIEW_ECONOMICS: TeamEconomicsPayload = {
  team_id: TEAM.id,
  heads_read: ['claude', 'claude-deepseek'],
  unattributed_turns: 0,
  oldest_turn_epoch_millis: Date.UTC(2025, 4, 22, 9, 14),
  roles: [
    { role: 'lead', ...tally(6, 132116, 56656, 0.412, Date.UTC(2025, 4, 22, 14, 0)) },
    { role: 'builder', ...tally(13, 194283, 78231, 0.324, Date.UTC(2025, 4, 22, 14, 0)) },
  ],
  slots: [
    { slot: 'slot-lead', ...tally(6, 132116, 56656, 0.412, Date.UTC(2025, 4, 22, 14, 0)), checks: 'pass', checks_source: CHECKS_SOURCE },
    { slot: 'slot-builder-1', ...tally(8, 121400, 49300, 0.183, Date.UTC(2025, 4, 22, 13, 58)), checks: 'pass', checks_source: CHECKS_SOURCE },
    { slot: 'slot-builder-2', ...tally(5, 72883, 28931, 0.141, Date.UTC(2025, 4, 22, 14, 0)), checks: 'pass', checks_source: CHECKS_SOURCE },
  ],
};

/** Turns in flight, sampled minute by minute over the hour comp-b's chart draws (13:02 to 14:02). */
const LAST_HOUR = [
  0, 5, 5, 8, 10, 18, 20, 20, 31, 31, 31, 45, 47, 47, 40, 37, 37, 37, 37, 37,
  37, 37, 37, 37, 37, 28, 17, 12, 12, 12, 15, 15, 15, 15, 15, 15, 15, 15, 17, 22,
  23, 23, 23, 30, 30, 30, 36, 39, 42, 43, 43, 43, 43, 45, 48, 50, 53, 53, 53, 53,
  50,
].map((turns, index) => ({ at: `${13 + Math.floor((2 + index) / 60)}:${String((2 + index) % 60).padStart(2, '0')}`, turns }));

/** The board the by-role and timeline views draw. */
export const viewsBoard: TeamPayload = {
  team: VIEW_TEAM,
  members: VIEW_MEMBERS,
  messages: MESSAGES,
  activity: VIEW_ACTIVITY,
  coldCacheHint: false,
};

/** What those views read beyond the board: turns, the day's economics, and the hour's samples. */
export const viewsData: TeamViewData = {
  turns: VIEW_TURNS,
  economics: VIEW_ECONOMICS,
  lastHour: LAST_HOUR,
  now: '14:02',
};
