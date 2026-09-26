// A sample team, as data: three sessions on three heads working one goal, with an open reviewer seat,
// a day of turns and hand-offs, and the daemon's lifetime tallies. Every figure agrees with every
// other: the role tallies are the slot tallies summed, the turns sit inside the day, and the
// hand-offs name the seats that sent them.
//
// It is a FIXTURE: it loads only when import.meta.env.DEV is true and the
// address carries ?fixture=hero (CONTRACTS.md section 4). The shipped dist
// carries none of these bytes, which the row's note proves by grepping the
// built file.
import type { TeamActivity, TeamEconomicsPayload, TeamMemberRow, TeamMessage, TeamPayload, TeamRow, TeamSlot } from '@entities/team';
import { clockText } from '@widgets/team-board';
import type { TeamTurn, TeamViewData } from '@widgets/team-board';

/** Epoch ms of HH:MM(:SS) on the sample's day, on the viewer's own clock like the board's day. */
const at = (h: number, m: number, s = 0): number => new Date(2025, 4, 22, h, m, s).getTime();

/** A slot as the daemon writes one, with the fields the sample leaves at their empty. */
const slot = (id: string, fields: Pick<TeamSlot, 'role' | 'head' | 'model' | 'account' | 'lead' | 'session' | 'instructions'>): TeamSlot => ({
  id,
  ...fields,
  instructions_updated_epoch_millis: null,
  sessions_history: fields.session === null ? [] : [fields.session],
});

const TEAM: TeamRow = {
  id: 'team_7f2c1b4a',
  name: 'storefront-api',
  goal: 'promoter bookkeeping lives on fly raw',
  features: ['promoter ledger', 'parity report'],
  repo: '~/Documents/dev/infra/storefront-api',
  archived: false,
  created_epoch_millis: at(9, 12, 33),
  updated_epoch_millis: at(14, 2, 5),
  slots: [
    slot('slot-lead', {
      role: 'lead', head: 'claudex', model: 'gpt-6-astra', account: 'acct-a', lead: true, session: 'api-lead',
      instructions: 'Own the packet queue. Hand each packet to a builder with its parity check, and merge only on a green gate.',
    }),
    slot('slot-builder-1', {
      role: 'builder', head: 'claude-grok', model: 'grok-4.7', account: 'acct-b', lead: false, session: 'api-builder',
      instructions: 'Build the packet you are handed, run parity, and report back to the lead.',
    }),
    slot('slot-builder-2', {
      role: 'builder', head: 'bonsai-2-27b', model: 'bonsai-2-27b', account: null, lead: false, session: 'api-builder-2',
      instructions: null,
    }),
    // The open seat names the head an operator would launch into it.
    slot('slot-reviewer', { role: 'reviewer', head: 'claude-grok', model: null, account: null, lead: false, session: null, instructions: null }),
  ],
  idempotency_key: null,
  create_fingerprint: null,
};

const LEAD: TeamMemberRow = {
  slot: 'slot-lead',
  name: 'api-lead',
  role: 'lead',
  lead: true,
  head: 'claudex',
  model: 'gpt-6-astra',
  account: 'acct-a',
  window: '5h 74%',
  lastTurn: '14:01',
  state: 'driving',
  sessionId: 's-1a7c9e2d',
  created: '09:12:33',
  startedAt: at(9, 12, 33),
  uptime: '4h 49m',
  turns: 6,
  tokensIn: 132116,
  tokensOut: 56656,
  costEst: 0.412,
  contextLeftPct: 35,
  scratchpadKb: 12.8,
  workspace: '~/.../storefront-api',
  branch: 'feature/gs-41',
  base: 'main',
  diff: '+412 -37',
  checks: 'pass',
};

const BUILDER: TeamMemberRow = {
  slot: 'slot-builder-1',
  name: 'api-builder',
  role: 'builder',
  lead: false,
  head: 'claude-grok',
  model: 'grok-4.7',
  account: 'acct-b',
  window: null,
  lastTurn: '13:58',
  state: 'GS-41 done',
  sessionId: 's-5f3b8a11',
  created: '09:18:02',
  startedAt: at(9, 18, 2),
  uptime: '4h 44m',
  turns: 8,
  tokensIn: 121400,
  tokensOut: 49300,
  costEst: 0.183,
  contextLeftPct: null,
  scratchpadKb: 8.1,
  workspace: '~/.../storefront-api',
  branch: 'feature/gs-41',
  base: 'main',
  diff: '+289 -37',
  checks: 'pass',
};

const MEMBERS: TeamMemberRow[] = [
  LEAD,
  BUILDER,
  {
    ...BUILDER,
    slot: 'slot-builder-2',
    name: 'api-builder-2',
    head: 'bonsai-2-27b',
    model: 'bonsai-2-27b',
    account: null,
    state: 'reading GS-42',
    sessionId: 's-9d04c3e7',
    created: '13:59:40',
    startedAt: at(13, 59, 40),
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

const MESSAGES: TeamMessage[] = [
  {
    at: at(13, 41),
    time: '13:41',
    from: 'api-lead',
    to: 'api-builder',
    packet: 'GS-41',
    text: 'packet GS-41: promoter bookkeeping lives on fly raw. build, run parity, report',
    fromHead: 'claudex',
  },
  {
    at: at(13, 58),
    time: '13:58',
    from: 'api-builder',
    to: 'api-lead',
    packet: 'GS-41',
    text: 'GS-41 done, see ledger',
    fromHead: 'claude-grok',
  },
  {
    at: at(14, 1),
    time: '14:01',
    from: 'api-lead',
    to: 'api-builder-2',
    packet: 'GS-42',
    text: 'packet GS-42: dearm the machine-update schedule',
    fromHead: 'claudex',
  },
];

const ACTIVITY: TeamActivity[] = [
  { time: '13:30:00', member: 'api-lead', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '13:30:00', member: 'api-builder', activity: 'searching for fly_raw', detail: 'rg -n "fly_raw"' },
  { time: '13:39:30', member: 'api-lead', activity: 'running checks/gate.sh', detail: 'checks/gate.sh --all' },
  { time: '13:39:30', member: 'api-builder', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '13:50:00', member: 'api-lead', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '13:50:00', member: 'api-builder', activity: 'running checks/gate.sh', detail: 'checks/gate.sh --all' },
  { time: '13:55:30', member: 'api-lead', activity: 'messaging a peer session', detail: 'to api-builder-2' },
  { time: '14:00:00', member: 'api-builder-2', activity: 'reading MANIFEST.toml', detail: 'MANIFEST.toml' },
  { time: '14:01:30', member: 'api-lead', activity: 'editing PromoterLedger.kt', detail: 'src/ledger/PromoterLedger.kt:142' },
  { time: '14:01:30', member: 'api-builder', activity: 'running checks/gate.sh', detail: 'checks/gate.sh --all' },
  { time: '14:01:30', member: 'api-builder-2', activity: 'searching for fly_raw', detail: 'rg -n "fly_raw"' },
];

/** A turn: its member, its start, how long it ran (so far, when it is live), its tokens. */
const turn = (id: string, member: string, start: number, ms: number, input: number, output: number, live = false): TeamTurn => ({
  id, member, start, ms, input, output, live,
});

const MINUTE = 60_000;

/** When the sample was read: the right end of its timeline. */
const NOW = at(14, 2);

const TURNS: TeamTurn[] = [
  turn('t-1801', 'api-lead', at(9, 14), 6 * MINUTE, 18402, 7120),
  turn('t-1802', 'api-builder', at(9, 21), 12 * MINUTE, 21044, 9310),
  turn('t-1806', 'api-lead', at(10, 5), 9 * MINUTE, 16290, 6021),
  turn('t-1809', 'api-builder', at(10, 40), 18 * MINUTE, 24880, 10114),
  turn('t-1812', 'api-lead', at(11, 30), 4 * MINUTE, 9120, 3302),
  turn('t-1815', 'api-builder', at(12, 10), 15 * MINUTE, 19620, 7744),
  turn('t-1821', 'api-lead', at(13, 33), 14 * MINUTE + 12_000, 42781, 18243),
  turn('t-1822', 'api-builder', at(13, 34), 11 * MINUTE + 7_000, 31209, 12884),
  turn('t-1823', 'api-lead', at(13, 43), 16 * MINUTE + 30_000, 48923, 20112),
  turn('t-1824', 'api-builder', at(13, 44), 13 * MINUTE + 55_000, 61332, 24991),
  // Running turns: their length is the time so far, so each reaches now.
  turn('t-1826', 'api-lead', at(14, 0), NOW - at(14, 0), 6412, 2301, true),
  turn('t-1827', 'api-builder', at(14, 0, 25), NOW - at(14, 0, 25), 4812, 1874, true),
  turn('t-1828', 'api-builder-2', at(13, 59, 40), NOW - at(13, 59, 40), 7923, 2945, true),
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

/** GET /api/teams/{id}/economics: the slots' tallies, and the roles' as their sums. The reviewer
 *  slot has held no session and so has no tally. */
const ECONOMICS: TeamEconomicsPayload = {
  team_id: TEAM.id,
  heads_read: ['claudex', 'claude-grok', 'bonsai-2-27b'],
  unattributed_turns: 2,
  oldest_turn_epoch_millis: at(9, 14),
  roles: [
    { role: 'lead', ...tally(6, 132116, 56656, 0.412, at(14, 0)) },
    { role: 'builder', ...tally(13, 194283, 78231, 0.324, at(14, 0)) },
  ],
  slots: [
    { slot: 'slot-lead', ...tally(6, 132116, 56656, 0.412, at(14, 0)), checks: 'pass', checks_source: CHECKS_SOURCE },
    { slot: 'slot-builder-1', ...tally(8, 121400, 49300, 0.183, at(13, 58)), checks: 'pass', checks_source: CHECKS_SOURCE },
    { slot: 'slot-builder-2', ...tally(5, 72883, 28931, 0.141, at(14, 0)), checks: 'pass', checks_source: CHECKS_SOURCE },
  ],
};

/** Turns in flight, minute by minute over the last hour (13:02 to 14:02), counted from the turns
 *  above the way pages/teams/board.ts lastHourOf counts perf rows: start to end, both included. */
const LAST_HOUR = Array.from({ length: 61 }, (_, index) => {
  const minute = at(13, 2) + index * MINUTE;
  return {
    at: clockText(minute),
    turns: TURNS.filter((t) => t.start <= minute && minute <= t.start + t.ms).length,
  };
});

/** The sample team, as the page draws it. */
export const sampleBoard = {
  team: TEAM,
  members: MEMBERS,
  messages: MESSAGES,
  activity: ACTIVITY,
  coldCacheHint: false,
  spliceHeads: new Set(['claudex', 'claude-grok', 'bonsai-2-27b']),
} satisfies TeamPayload;

/** What the views read beyond the board: the day's turns, the lifetime tallies, the last hour. */
export const sampleData: TeamViewData = {
  turns: TURNS,
  economics: ECONOMICS,
  lastHour: LAST_HOUR,
  inFlight: TURNS.filter((t) => t.live).length,
  now: NOW,
};
